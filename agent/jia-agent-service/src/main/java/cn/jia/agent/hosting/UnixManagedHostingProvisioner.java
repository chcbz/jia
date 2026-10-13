package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.ManagedHostingAdapterProperties;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Runtime hosting v1 only. No retired broker, credentials, arbitrary paths or public callback. */
@Component
public final class UnixManagedHostingProvisioner implements ManagedHostingProvisioner {
    static final String PROTOCOL = "runtime-hosting-v1";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int MAX_FRAME = 16384;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AgentHostingRentProperties rent;
    private final ManagedHostingAdapterProperties config;
    private final ManagedHostingCredentials credentials;
    private final AgentRuntimeAuthenticationService sessions;
    public UnixManagedHostingProvisioner(AgentHostingRentProperties rent, ManagedHostingAdapterProperties config,
            ManagedHostingCredentials credentials, AgentRuntimeAuthenticationService sessions) {
        this.rent = rent; this.config = config; this.credentials = credentials; this.sessions = sessions;
    }
    @Override public boolean available() { return rent.configured() && config.configured() && credentials.available(); }
    @Override public boolean availableFor(String tenantId, String clientId, String ownerJiacn) {
        return available() && config.allowsScope(tenantId, clientId, ownerJiacn);
    }
    @Override public boolean probeCapabilities(String tenantId, String clientId, String ownerJiacn) {
        outsideTransaction();
        if (!availableFor(tenantId, clientId, ownerJiacn)) return false;
        try {
            var request = new LinkedHashMap<String, Object>();
            request.put("protocol", PROTOCOL); request.put("method", "capabilities");
            request.put("tenantId", tenantId); request.put("clientId", clientId); request.put("ownerJiacn", ownerJiacn);
            var response = exchange(request);
            return response != null && response.keySet().equals(Set.of("protocol", "method", "tenantId", "clientId", "ownerJiacn", "available", "hostId"))
                    && echoes(request, response) && Boolean.TRUE.equals(response.get("available")) && exact(response.get("hostId"), 100);
        } catch (Exception unavailable) { return false; }
    }
    @Override public Observation prepareAndObserve(Preparation p) {
        outsideTransaction();
        if (!validPreparation(p) || !availableFor(p.tenantId(), p.clientId(), p.ownerJiacn())) return unknown(p);
        try {
            Candidate candidate = decodePrepared(p, exchange(request(p, "prepare", null)));
            if (candidate == null) return unknown(p);
            credentials.ensureInstallation(p, candidate); // DB-only atomic authorization/linkage, committed before ensure.
            var observed = exchange(request(p, "observe", candidate));
            Observation proof = trustedObservation(p, candidate, "observe", observed);
            if (proof.outcome() == Outcome.SERVICE_READY || recoveryRequired(observed)) return proof;
            return trustedObservation(p, candidate, "ensure", exchange(request(p, "ensure", candidate)));
        } catch (Exception uncertain) {
            // Lost prepare/enroll/ensure response remains UNKNOWN. No new key/order/refund/reenrollment.
            return unknown(p);
        }
    }
    private static boolean recoveryRequired(Map<String, Object> response) {
        return response != null && "RECOVERY_REQUIRED".equals(response.get("outcome"));
    }
    private Observation trustedObservation(Preparation p, Candidate candidate, String method, Map<String, Object> response) {
        Observation observation = decode(p, candidate, method, response);
        if (observation.outcome() != Outcome.SERVICE_READY) return observation;
        var proof = sessions.currentRegisteredProof(p.tenantId(), p.clientId(), p.agentId());
        if (proof == null || proof.scope() == null || !p.tenantId().equals(proof.scope().tenantId())
                || !p.clientId().equals(proof.scope().clientId()) || !p.agentId().equals(proof.scope().agentId())
                || !p.ownerJiacn().equals(proof.scope().ownerJiacn()) || !candidate.installationId().equals(proof.installationId())
                || !candidate.hostId().equals(proof.hostId()) || !Objects.equals(response.get("runtimeInstanceId"), proof.scope().runtimeInstanceId())
                || integral(response.get("sessionGeneration")) != proof.sessionGeneration()) return unknown(p);
        return observation;
    }
    static Map<String, Object> request(Preparation p, String method, Candidate candidate) {
        if (!validPreparation(p) || !Set.of("prepare", "ensure", "observe").contains(method)
                || !"prepare".equals(method) && candidate == null) throw new IllegalArgumentException("Invalid hosting control request");
        Map<String, Object> request = association(p);
        request.put("protocol", PROTOCOL); request.put("method", method);
        if (!"prepare".equals(method)) {
            request.put("installationId", candidate.installationId()); request.put("manifestSha256", candidate.manifestSha256());
            request.put("provisionGeneration", candidate.provisionGeneration());
        }
        return request;
    }
    private static Map<String, Object> association(Preparation p) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", p.tenantId()); fields.put("clientId", p.clientId()); fields.put("ownerJiacn", p.ownerJiacn());
        fields.put("canonicalAgentId", p.agentId()); fields.put("initialIntentId", p.intentId()); fields.put("leaseId", p.leaseId());
        fields.put("bindingId", p.bindingId()); fields.put("reservedAt", p.reservedAt()); fields.put("operationId", p.operationId());
        fields.put("operationKind", p.operationId().equals(p.intentId()) ? "INITIAL" : "REPROVISION");
        fields.put("requestedAt", p.requestedAt()); fields.put("validUntil", p.validUntil());
        return fields;
    }
    static Candidate decodePrepared(Preparation p, Map<String, Object> response) {
        if (!validPreparation(p) || response == null) return null;
        var expected = request(p, "prepare", null);
        var allowed = new java.util.HashSet<>(expected.keySet());
        allowed.addAll(Set.of("outcome", "installationId", "manifest", "manifestSha256", "enrollmentSecretSha256",
                "enrollmentExpiresAt", "provisionGeneration", "hostId"));
        if (!response.keySet().equals(allowed) || !echoes(expected, response) || !"PREPARED".equals(response.get("outcome"))
                || !installation(response.get("installationId")) || !digest(response.get("manifestSha256"))
                || !digest(response.get("enrollmentSecretSha256")) || integral(response.get("enrollmentExpiresAt")) <= 0
                || integral(response.get("provisionGeneration")) <= 0 || !exact(response.get("hostId"), 100)) return null;
        if (p.operationId().equals(p.intentId()) && integral(response.get("provisionGeneration")) != 1) return null;
        if (!(response.get("manifest") instanceof Map<?, ?> manifest)
                || !manifest.keySet().equals(Set.of("runtimeProtocolVersion", "manifestVersion", "installationId",
                        "tenantId", "clientId", "canonicalAgentId", "manifestSha256"))
                || !"v1".equals(manifest.get("runtimeProtocolVersion")) || !"1".equals(manifest.get("manifestVersion"))
                || !response.get("installationId").equals(manifest.get("installationId"))
                || !p.tenantId().equals(manifest.get("tenantId")) || !p.clientId().equals(manifest.get("clientId"))
                || !p.agentId().equals(manifest.get("canonicalAgentId"))
                || !("sha256:" + response.get("manifestSha256")).equals(manifest.get("manifestSha256"))) return null;
        try {
            Map<String, Object> unsigned = new TreeMap<>();
            manifest.forEach((key, value) -> { if (!"manifestSha256".equals(key)) unsigned.put((String) key, value); });
            String computed = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(unsigned).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!computed.equals(response.get("manifestSha256"))) return null;
        } catch (Exception invalidManifest) { return null; }
        return new Candidate((String) response.get("installationId"), (String) response.get("manifestSha256"),
                (String) response.get("enrollmentSecretSha256"), integral(response.get("enrollmentExpiresAt")),
                integral(response.get("provisionGeneration")), (String) response.get("hostId"));
    }
    static Observation decode(Preparation p, Candidate candidate, String method, Map<String, Object> response) {
        if (candidate == null || response == null || !validPreparation(p)) return unknown(p);
        var expected = request(p, method, candidate);
        var allowed = new java.util.HashSet<>(expected.keySet());
        allowed.addAll(Set.of("outcome", "hostId", "runtimeInstanceId", "sessionGeneration", "serviceReadyAt", "registeredAt",
                "executorReady", "durableReady", "evidenceRef"));
        if (!response.keySet().equals(allowed) || !echoes(expected, response) || !candidate.hostId().equals(response.get("hostId"))
                || !"SERVICE_READY".equals(response.get("outcome")) || !exact(response.get("runtimeInstanceId"), 100)
                || p.agentId().equals(response.get("runtimeInstanceId")) || integral(response.get("sessionGeneration")) <= 0
                || !Boolean.TRUE.equals(response.get("executorReady")) || !Boolean.TRUE.equals(response.get("durableReady"))
                || !exact(response.get("evidenceRef"), 100)) return unknown(p);
        long readyAt = integral(response.get("serviceReadyAt")), registeredAt = integral(response.get("registeredAt"));
        if (registeredAt < p.requestedAt() || readyAt < registeredAt || readyAt > System.currentTimeMillis()
                || p.validUntil() != null && readyAt >= p.validUntil()) return unknown(p);
        return new Observation(p, Outcome.SERVICE_READY, (String) response.get("evidenceRef"), readyAt);
    }
    private static boolean echoes(Map<String, Object> expected, Map<String, Object> response) {
        return expected.entrySet().stream().allMatch(e -> response.containsKey(e.getKey())
                && (e.getValue() instanceof Number n ? integral(response.get(e.getKey())) == n.longValue()
                    : Objects.equals(e.getValue(), response.get(e.getKey()))));
    }
    private static boolean validPreparation(Preparation p) {
        return p != null && exact(p.tenantId(), 50) && exact(p.clientId(), 50) && exact(p.ownerJiacn(), 50)
                && !"*".equals(p.ownerJiacn()) && exact(p.agentId(), 100) && exact(p.intentId(), 100) && exact(p.leaseId(), 100)
                && p.bindingId() != null && p.bindingId().matches("[1-9][0-9]{0,18}") && exact(p.operationId(), 100)
                && integral(p.reservedAt()) > 0 && integral(p.requestedAt()) >= p.reservedAt()
                && (p.operationId().equals(p.intentId()) ? p.validUntil() == null && p.requestedAt() == p.reservedAt()
                    : p.validUntil() != null && integral(p.validUntil()) > p.requestedAt());
    }
    private static long integral(Object value) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) return -1;
        long number = ((Number) value).longValue();
        return number >= 0 && number <= MAX_SAFE_INTEGER ? number : -1;
    }
    private static boolean exact(Object value, int max) {
        return value instanceof String s && !s.isBlank() && s.equals(s.strip()) && s.length() <= max
                && s.chars().noneMatch(c -> Character.isISOControl(c) || Character.isSurrogate((char)c));
    }
    private static boolean digest(Object value) { return value instanceof String s && s.matches("[0-9a-f]{64}"); }
    private static boolean installation(Object value) { return value instanceof String s && s.matches("rti_[0-9a-f]{32}"); }

    Map<String, Object> exchange(Map<String, Object> request) throws IOException {
        outsideTransaction();
        Path socket = Path.of(config.socketPath());
        validateSocket(socket, config.runnerUid(), config.socketGroupGid());
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
    static void validateSocket(Path socket, long runnerUid, long trustedGid) throws IOException {
        Path parent = socket.getParent();
        if (parent == null || !parent.equals(parent.toRealPath()) || Files.isSymbolicLink(socket)) throw new IOException("Unsafe managed socket path");
        var root = Files.readAttributes(parent, "unix:mode,uid,gid", LinkOption.NOFOLLOW_LINKS);
        var node = Files.readAttributes(socket, "unix:mode,uid,gid", LinkOption.NOFOLLOW_LINKS);
        int mode = ((Number) node.get("mode")).intValue();
        if (((Number) root.get("gid")).longValue() != trustedGid || ((Number) node.get("gid")).longValue() != trustedGid
                || ((Number) root.get("uid")).longValue() != runnerUid || ((Number) node.get("uid")).longValue() != runnerUid
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
