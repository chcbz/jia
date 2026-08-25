package cn.jia.agent.service;

import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.agent.common.AgentErrorConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class AgentHostedProfilePublisher {
    private static final String DEFAULT_SECTION = """
            [default]
            codexBin=/usr/local/bin/codex
            codexWorkdir=/home/isp
            codexSandbox=workspace-write
            codexApproval=never
            codexSessionMode=resume
            codexTimeoutMs=900000
            """.stripTrailing();

    private final Path runtimeDir;
    private final Path profilesFile;
    private final Path clientsDir;
    static final String CAPABILITY_MANIFEST = "hosted-runtime-capabilities.properties";
    private static final String CAPABILITY_VERSION = "1";
    private static final List<String> CAPABILITY_KEYS = List.of(
            "contractVersion", "perProfileApiKey", "disabledStage",
            "hotReloadDisconnect", "runtimeSha256");

    private final Path runtimeSource;
    private final Path capabilityManifest;

    public AgentHostedProfilePublisher(
            @Value("${jia.agent.hosted.runtime-dir:/home/isp/apps/codex-ws-agent}") String runtimeDir,
            @Value("${jia.agent.hosted.clients-dir:/home/isp/hosts/cyf/agent-clients}") String clientsDir) {
        this(Path.of(runtimeDir), Path.of(clientsDir));
    }

    AgentHostedProfilePublisher(Path runtimeDir, Path clientsDir) {
        this.runtimeDir = runtimeDir;
        this.profilesFile = runtimeDir.resolve("codex-profiles.conf");
        this.clientsDir = clientsDir;
        this.runtimeSource = runtimeDir.resolve("agent-client.mjs");
        this.capabilityManifest = runtimeDir.resolve(CAPABILITY_MANIFEST);
    }

    public PublishedPaths publish(AgentHostedProfileEntity hosted, AgentPersonaEntity persona,
            long expectedGeneration, long nextGeneration, boolean enabled, String apiKey) {
        requireExactHosted(hosted);
        if (expectedGeneration < 0 || nextGeneration != expectedGeneration + 1) {
            fail("Hosted profile publication generation is invalid");
        }
        if (!safeConfigValue(apiKey)) fail("Dedicated hosted API key is unavailable");
        if (persona == null || !safeConfigValue(persona.getName())
                || (persona.getTitle() != null && !safeConfigValue(persona.getTitle()))) {
            fail("Hosted persona configuration is invalid");
        }
        verifyRuntimeCapabilities();
        Path workdir = clientsDir.resolve(hosted.getCanonicalAgentId());
        Path codexHome = runtimeDir.resolve(".codex-hosted-" + hosted.getProfileKey());
        try {
            Files.createDirectories(workdir);
            Files.createDirectories(codexHome);
            copyBootstrap("config.toml", codexHome);
            copyBootstrap("auth.json", codexHome);
            Files.createDirectories(profilesFile.getParent());
            Path lockPath = profilesFile.resolveSibling(profilesFile.getFileName() + ".lock");
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                String current = Files.exists(profilesFile)
                        ? Files.readString(profilesFile, StandardCharsets.UTF_8) : DEFAULT_SECTION;
                String desired = section(hosted, persona, workdir, codexHome, enabled, apiKey, nextGeneration);
                String next = replaceCas(current, hosted, expectedGeneration, nextGeneration, desired);
                if (!next.equals(current)) atomicPublish(next);
            }
            return new PublishedPaths(workdir.toString(), codexHome.toString(), profilesFile.toString());
        } catch (IOException e) {
            fail("Hosted profile publication failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private void verifyRuntimeCapabilities() {
        try {
            byte[] runtimeBytes = Files.readAllBytes(runtimeSource);
            List<String> lines = Files.readAllLines(capabilityManifest, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.isEmpty()) continue;
                int separator = line.indexOf('=');
                if (separator <= 0 || separator != line.lastIndexOf('=')) {
                    fail("Hosted runtime capability contract is malformed");
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!CAPABILITY_KEYS.contains(key) || value.isEmpty()
                        || !key.equals(key.strip()) || !value.equals(value.strip())
                        || values.putIfAbsent(key, value) != null) {
                    fail("Hosted runtime capability contract is malformed");
                }
            }
            if (!values.keySet().equals(new java.util.LinkedHashSet<>(CAPABILITY_KEYS))
                    || !CAPABILITY_VERSION.equals(values.get("contractVersion"))
                    || !"true".equals(values.get("perProfileApiKey"))
                    || !"true".equals(values.get("disabledStage"))
                    || !"true".equals(values.get("hotReloadDisconnect"))) {
                fail("Hosted runtime capability contract is unsupported");
            }
            String expectedDigest = values.get("runtimeSha256");
            String actualDigest = sha256(runtimeBytes);
            if (!expectedDigest.matches("[0-9a-f]{64}") || !expectedDigest.equals(actualDigest)) {
                fail("Hosted runtime capability contract does not match authoritative runtime");
            }
        } catch (IOException e) {
            fail("Authoritative hosted runtime capability contract is unavailable");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String replaceCas(String profiles, AgentHostedProfileEntity hosted,
            long expectedGeneration, long nextGeneration, String desired) {
        List<Range> sections = sections(profiles);
        Range target = null;
        for (Range range : sections) {
            String body = profiles.substring(range.start, range.end);
            String header = "[agent." + hosted.getProfileKey() + "]";
            boolean sameHeader = body.lines().findFirst().orElse("").trim().equals(header);
            boolean sameBinding = body.contains("# cyfHostedBindingId=" + hosted.getBindingId());
            boolean sameAgent = body.lines().anyMatch(line -> line.equals("agentId=" + hosted.getCanonicalAgentId()));
            if ((sameBinding || sameAgent) && !sameHeader) fail("Hosted profile identity collision");
            if (sameHeader) {
                if (target != null) fail("Duplicate hosted profile key");
                target = range;
            }
        }
        if (target == null) {
            if (expectedGeneration != 0) fail("Hosted profile generation conflict: section missing");
            return profiles.stripTrailing() + "\n\n" + desired.strip() + "\n";
        }
        String existing = profiles.substring(target.start, target.end);
        String bindingMarker = "# cyfHostedBindingId=" + hosted.getBindingId();
        String agentMarker = "# cyfHostedCanonicalAgentId=" + hosted.getCanonicalAgentId();
        if (!existing.contains(bindingMarker) || !existing.contains(agentMarker)) {
            fail("Legacy or colliding hosted profile is not adoptable");
        }
        long actual = markerGeneration(existing);
        if (actual == nextGeneration && normalize(existing).equals(normalize(desired))) return profiles;
        if (actual != expectedGeneration) fail("Hosted profile generation conflict");
        return profiles.substring(0, target.start) + desired.strip() + "\n" + profiles.substring(target.end);
    }

    private List<Range> sections(String text) {
        List<Range> result = new ArrayList<>();
        int cursor = 0;
        while (true) {
            int start = text.indexOf("[agent.", cursor);
            if (start < 0) break;
            if (start > 0 && text.charAt(start - 1) != '\n') { cursor = start + 1; continue; }
            int nextAgent = text.indexOf("\n[agent.", start + 1);
            int nextProfile = text.indexOf("\n[profile.", start + 1);
            int end = text.length();
            if (nextAgent >= 0) end = Math.min(end, nextAgent + 1);
            if (nextProfile >= 0) end = Math.min(end, nextProfile + 1);
            result.add(new Range(start, end));
            cursor = end;
        }
        return result;
    }

    private long markerGeneration(String section) {
        String prefix = "# cyfHostedGeneration=";
        List<String> values = section.lines().filter(line -> line.startsWith(prefix)).toList();
        if (values.size() != 1) fail("Hosted profile generation marker is missing or duplicated");
        try { return Long.parseLong(values.getFirst().substring(prefix.length())); }
        catch (NumberFormatException e) { fail("Hosted profile generation marker is invalid"); return -1; }
    }

    private String section(AgentHostedProfileEntity hosted, AgentPersonaEntity persona,
            Path workdir, Path codexHome, boolean enabled, String apiKey, long generation) {
        return """
                [agent.%s]
                # cyfHostedBindingId=%d
                # cyfHostedCanonicalAgentId=%s
                # cyfHostedGeneration=%d
                agentId=%s
                codexWorkdir=%s
                agentName=%s
                personaName=%s
                codexHome=%s
                enabled=%s
                apiKey=%s
                """.formatted(hosted.getProfileKey(), hosted.getBindingId(), hosted.getCanonicalAgentId(),
                generation, hosted.getCanonicalAgentId(), workdir, persona.getName(),
                persona.getTitle() == null || persona.getTitle().isBlank() ? persona.getName() : persona.getTitle(),
                codexHome, enabled, apiKey).stripTrailing();
    }

    private void atomicPublish(String content) throws IOException {
        Path directory = profilesFile.getParent();
        Path temp = Files.createTempFile(directory, profilesFile.getFileName() + ".", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temp, profilesFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic move is unsupported", e);
            }
            try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
                directoryChannel.force(true);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void copyBootstrap(String name, Path codexHome) throws IOException {
        Path source = runtimeDir.resolve(".codex").resolve(name);
        Path target = codexHome.resolve(name);
        if (Files.exists(source) && !Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void requireExactHosted(AgentHostedProfileEntity h) {
        if (h == null || h.getBindingId() == null || h.getBindingId() <= 0
                || blank(h.getTenantId()) || blank(h.getClientId()) || blank(h.getOwnerJiacn())
                || blank(h.getCanonicalAgentId()) || blank(h.getProfileKey()) || blank(h.getApiKeyId())
                || !h.getCanonicalAgentId().matches("agt_[0-9a-f]{32}")) {
            fail("Hosted profile durable identity is incomplete");
        }
        AgentHostedBindingTransaction.Scope scope;
        try {
            scope = new AgentHostedBindingTransaction.Scope(
                    h.getTenantId(), h.getClientId(), h.getOwnerJiacn());
        } catch (IllegalArgumentException invalidScope) {
            fail("Hosted profile durable scope is invalid");
            return;
        }
        if (!h.getProfileKey().equals(AgentHostedBindingTransaction.scopeDigest(scope) + h.getBindingId())) {
            fail("Hosted profile durable key is invalid");
        }
    }
    private boolean blank(String value) { return value == null || value.isBlank() || !value.equals(value.strip()); }
    private boolean safeConfigValue(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private String normalize(String value) { return value.strip().replace("\r\n", "\n"); }
    private void fail(String message) { throw new AgentBizException(AgentErrorConstants.AGENT_ERROR, message); }

    private record Range(int start, int end) {}
    public record PublishedPaths(String workdir, String codexHome, String profilesFile) {}
}
