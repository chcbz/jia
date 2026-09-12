package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.ManagedHostingAdapterProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Concrete restricted runner channel. All socket/filesystem I/O is outside funds/admission transactions. */
@Component
public final class UnixManagedHostingProvisioner implements ManagedHostingProvisioner {
    private static final int MAX_FRAME = 16384;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AgentHostingRentProperties rent;
    private final ManagedHostingAdapterProperties config;
    private final ManagedHostingCredentials credentials;
    public UnixManagedHostingProvisioner(AgentHostingRentProperties rent, ManagedHostingAdapterProperties config,
            ManagedHostingCredentials credentials) { this.rent = rent; this.config = config; this.credentials = credentials; }
    @Override public boolean available() { return rent.configured() && config.configured() && credentials.available(); }

    @Override public boolean availableFor(String tenantId, String clientId, String ownerJiacn) {
        return available() && config.tenantId().equals(tenantId) && config.clientId().equals(clientId)
                && config.ownerJiacn().equals(ownerJiacn);
    }

    @Override public Observation prepareAndObserve(Preparation preparation) {
        outsideTransaction();
        if (!available() || !config.tenantId().equals(preparation.tenantId())
                || !config.clientId().equals(preparation.clientId()) || !config.ownerJiacn().equals(preparation.ownerJiacn())) return unknown(preparation);
        try {
            String key = credentials.credential(preparation); // Short DB-only transaction, committed before channel access.
            var request = request(preparation, key, "observe");
            var observed = decode(preparation, exchange(request));
            if (observed.outcome() != Outcome.UNKNOWN) return observed;
            request.put("method", "ensure");
            return decode(preparation, exchange(request));
        } catch (Exception uncertain) {
            // Never expose key/paths/protocol contents and never interpret failure as no-effect.
            return unknown(preparation);
        }
    }

    static Map<String, Object> request(Preparation p, String apiKey, String method) {
        Map<String, Object> request = association(p);
        request.put("protocol", "1"); request.put("method", method); request.put("apiKey", apiKey);
        return request;
    }
    private static Map<String, Object> association(Preparation p) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tenantId", p.tenantId()); request.put("clientId", p.clientId()); request.put("ownerJiacn", p.ownerJiacn());
        request.put("agentId", p.agentId()); request.put("intentId", p.intentId()); request.put("leaseId", p.leaseId());
        request.put("bindingId", p.bindingId()); request.put("reservedAt", Long.toString(p.reservedAt()));
        request.put("operationId", p.operationId()); request.put("requestedAt", Long.toString(p.requestedAt()));
        request.put("validUntil", p.validUntil() == null ? null : p.validUntil().toString());
        return request;
    }
    static Observation decode(Preparation p, Map<String, Object> response) {
        if (response == null || !"1".equals(response.get("protocol"))) return unknown(p);
        var expected = association(p);
        if (expected.entrySet().stream().anyMatch(e -> !response.containsKey(e.getKey())
                || !java.util.Objects.equals(e.getValue(), response.get(e.getKey())))) return unknown(p);
        Set<String> allowed = new java.util.HashSet<>(expected.keySet());
        allowed.addAll(Set.of("protocol", "outcome", "evidenceRef", "serviceReadyAt", "runtimeInstanceId", "engineThreadId", "profileRef"));
        if (!allowed.containsAll(response.keySet())) return unknown(p);
        String outcome = response.get("outcome") instanceof String text ? text : "UNKNOWN";
        if (!"SERVICE_READY".equals(outcome) && !"FAILED_NO_EFFECT".equals(outcome)) return unknown(p);
        if (!(response.get("evidenceRef") instanceof String evidence)) return unknown(p);
        try {
            HostingRentHttp.exact(evidence, 100);
            if ("FAILED_NO_EFFECT".equals(outcome)) {
                if (response.containsKey("serviceReadyAt") || response.containsKey("engineThreadId")) return unknown(p);
                return new Observation(p, Outcome.FAILED_NO_EFFECT, evidence);
            }
            if (!(response.get("serviceReadyAt") instanceof String time)
                    || !(response.get("runtimeInstanceId") instanceof String runtime)
                    || !(response.get("engineThreadId") instanceof String thread)
                    || !(p.agentId() + "/" + p.intentId()).equals(response.get("profileRef"))) return unknown(p);
            HostingRentHttp.key(runtime); HostingRentHttp.exact(thread, 100);
            long readyAt = HostingRentHttp.positive(time);
            if (readyAt < p.requestedAt() || readyAt < p.reservedAt() || readyAt > System.currentTimeMillis()
                    || p.validUntil() != null && readyAt >= p.validUntil()) return unknown(p);
            return new Observation(p, Outcome.SERVICE_READY, evidence, readyAt);
        } catch (RuntimeException invalidProof) { return unknown(p); }
    }

    Map<String, Object> exchange(Map<String, Object> request) throws IOException {
        outsideTransaction();
        Path socket = Path.of(config.socketPath());
        validateSocket(socket, config.runnerUid());
        byte[] frame = (JSON.writeValueAsString(request) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (frame.length > MAX_FRAME) throw new IOException("Managed frame too large");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs());
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX); Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                while (!channel.finishConnect()) await(channel, selector, SelectionKey.OP_CONNECT, deadline);
            }
            ByteBuffer out = ByteBuffer.wrap(frame);
            while (out.hasRemaining()) {
                if (channel.write(out) == 0) await(channel, selector, SelectionKey.OP_WRITE, deadline);
                checkDeadline(deadline);
            }
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(2048);
            while (true) {
                int count = channel.read(buffer);
                if (count < 0) throw new IOException("Incomplete managed response");
                if (count == 0) { await(channel, selector, SelectionKey.OP_READ, deadline); continue; }
                checkDeadline(deadline);
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte value = buffer.get();
                    if (value == '\n') {
                        if (buffer.hasRemaining()) throw new IOException("Multiple managed frames");
                        return JSON.readValue(response.toByteArray(), new TypeReference<Map<String, Object>>() { });
                    }
                    if (response.size() >= MAX_FRAME) throw new IOException("Managed response too large");
                    response.write(value);
                }
                buffer.clear();
            }
        }
    }
    static void validateSocket(Path socket, long runnerUid) throws IOException {
        Path parent = socket.getParent();
        if (parent == null || !parent.equals(parent.toRealPath()) || Files.isSymbolicLink(socket)) throw new IOException("Unsafe managed socket path");
        var root = Files.readAttributes(parent, "unix:mode,uid", LinkOption.NOFOLLOW_LINKS);
        var node = Files.readAttributes(socket, "unix:mode,uid", LinkOption.NOFOLLOW_LINKS);
        int mode = ((Number) node.get("mode")).intValue();
        if (((Number) root.get("uid")).longValue() != runnerUid || ((Number) node.get("uid")).longValue() != runnerUid
                || (((Number) root.get("mode")).intValue() & 0027) != 0
                || (mode & 0170000) != 0140000 || (mode & 0117) != 0) throw new IOException("Unsafe managed socket permissions");
    }
    private static void await(SocketChannel channel, Selector selector, int operation, long deadline) throws IOException {
        checkDeadline(deadline);
        channel.register(selector, operation);
        selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        selector.selectedKeys().clear();
        checkDeadline(deadline);
    }
    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("Managed channel deadline");
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Managed I/O inside transaction");
    }
    private static Observation unknown(Preparation p) { return new Observation(p, Outcome.UNKNOWN, null); }
}
