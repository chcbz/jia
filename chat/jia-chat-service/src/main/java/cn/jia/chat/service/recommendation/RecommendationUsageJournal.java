package cn.jia.chat.service.recommendation;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.usage.UsageCostConflict;
import cn.jia.economy.usage.UsageCostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Private source receipts, not a provider retry queue. The cost ledger owns idempotency.
 * This directory must survive process restarts and must not be shared with model workspaces.
 */
@Component
public final class RecommendationUsageJournal {
    private static final Logger LOG = LoggerFactory.getLogger(RecommendationUsageJournal.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UsageCostService costs;
    private final FixedTenantAdmissionPolicy scope;
    private final Path directory;

    public RecommendationUsageJournal(UsageCostService costs, FixedTenantAdmissionPolicy scope,
            @Value("${jia.chat.recommendation.usage-directory:}") String root) {
        this.costs = costs;
        this.scope = scope;
        // Partition before discovery, including optional client narrowing. Never scan another tenant.
        this.directory = root.isBlank() ? null : Path.of(root).toAbsolutePath().resolve(digest(
                (scope.businessTenantId() + "\n" + String.valueOf(scope.clientId())).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Called before a paid call, so missing source storage cannot silently discard its receipt. */
    public void prepare() {
        if (directory == null) throw new IllegalStateException("Recommendation usage directory is not configured");
        try {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            // Verify actual writes, rather than a permission-bit guess. No model call has happened yet.
            Path probe = Files.createTempFile(directory, ".prepare-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.delete(probe);
        } catch (IOException failure) { throw new UncheckedIOException("Cannot prepare recommendation usage storage", failure); }
    }

    public void record(UsageCostService.Observation observation) {
        scope.requireAdmitted(observation.binding().scope().tenantId(), observation.binding().scope().clientId());
        Path temporary = null;
        try {
            byte[] bytes = JSON.writeValueAsBytes(observation);
            Path receipt = directory.resolve(digest(bytes) + ".json");
            temporary = Files.createTempFile(directory, ".receipt-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            // Publish is durable before the SQL commit/ACK. A failed SQL call leaves this receipt.
            try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            deliver(receipt, observation);
        } catch (IOException failure) { throw new UncheckedIOException("Cannot persist recommendation usage receipt", failure); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException cleanupFailure) { LOG.warn("Recommendation usage temporary file retained"); }
        }
    }

    @Scheduled(fixedDelayString = "${jia.chat.recommendation.usage-recovery-delay-ms:60000}")
    public void recover() {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    if (!file.getFileName().toString().equals(digest(bytes) + ".json"))
                        throw new IllegalArgumentException("Usage receipt digest mismatch");
                    var observation = JSON.readValue(bytes, UsageCostService.Observation.class);
                    scope.requireAdmitted(observation.binding().scope().tenantId(), observation.binding().scope().clientId());
                    if (!deliver(file, observation)) break; // One unavailable DB does not cause a backlog-sized retry storm.
                } catch (java.nio.file.NoSuchFileException concurrentlyAcknowledged) {
                    // The foreground delivery may have just acknowledged the same immutable receipt.
                } catch (IOException | IllegalArgumentException invalid) {
                    LOG.error("Recommendation usage receipt retained for reconciliation: {}", file.getFileName());
                }
            }
        } catch (IOException failure) { LOG.error("Cannot scan recommendation usage storage"); }
    }

    private boolean deliver(Path file, UsageCostService.Observation observation) throws IOException {
        try {
            costs.record(observation);
        } catch (UsageCostConflict conflict) {
            LOG.error("Recommendation usage conflict retained: {}", file.getFileName());
            return true; // Do not starve independent valid receipts behind a conflict.
        } catch (RuntimeException unavailable) {
            LOG.warn("Recommendation usage delivery deferred: {}", file.getFileName());
            return false;
        }
        try { Files.deleteIfExists(file); } // Unknown ACK before deletion replays the same ledger receipt.
        catch (IOException retained) { LOG.warn("Recommendation usage acknowledged file retained: {}", file.getFileName()); }
        return true;
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
