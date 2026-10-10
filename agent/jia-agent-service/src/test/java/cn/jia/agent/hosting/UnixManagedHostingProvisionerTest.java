package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.ManagedHostingAdapterProperties;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnixManagedHostingProvisionerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ManagedHostingCredentials credentials = mock(ManagedHostingCredentials.class);
    private final AgentRuntimeAuthenticationService sessions = mock(AgentRuntimeAuthenticationService.class);
    private Map<String, Object> fixture;
    @BeforeEach void fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/hosting/gss-hosting-control-v1.json")) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            assertEquals("5c4b833e6db5bab3122c6dee17ef49198462aee3c3edf58f628ed8571e241403",
                    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            fixture = JSON.readValue(bytes, new TypeReference<Map<String, Object>>() {});
        }
        when(credentials.available()).thenReturn(true);
        when(sessions.currentRegisteredProof("0", "fixture-client", "agt_fixture")).thenReturn(proof("fixture-owner", "fixture-host", "fixture-instance", 2));
    }
    @SuppressWarnings("unchecked") private Map<String, Object> frame(String name) { return new LinkedHashMap<>((Map<String, Object>)fixture.get(name)); }
    private ManagedHostingProvisioner.Preparation preparation() { return preparation("prepareRequest"); }
    private ManagedHostingProvisioner.Preparation preparation(String name) {
        var f = frame(name);
        return new ManagedHostingProvisioner.Preparation((String)f.get("tenantId"), (String)f.get("clientId"), (String)f.get("ownerJiacn"),
                (String)f.get("canonicalAgentId"), (String)f.get("initialIntentId"), (String)f.get("leaseId"), (String)f.get("bindingId"),
                ((Number)f.get("reservedAt")).longValue(), (String)f.get("operationId"), ((Number)f.get("requestedAt")).longValue(),
                f.get("validUntil") == null ? null : ((Number)f.get("validUntil")).longValue());
    }
    private ManagedHostingProvisioner.Candidate candidate() { return UnixManagedHostingProvisioner.decodePrepared(preparation(), frame("preparedResponse")); }
    private AgentRuntimeAuthenticationService.Proof proof(String owner, String host, String instance, long generation) {
        return new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0", "fixture-client", owner, "agt_fixture", instance),
                "rti_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", host, generation, "a".repeat(64), 1, 0);
    }
    private UnixManagedHostingProvisioner adapter(Path socket, long uid, long gid, String owner) {
        return new UnixManagedHostingProvisioner(new AgentHostingRentProperties(true, null, null, null),
                new ManagedHostingAdapterProperties(socket.toString(), uid, "0", "fixture-client", owner, 1000, gid), credentials, sessions);
    }
    private UnixManagedHostingProvisioner adapter() { return spy(adapter(Path.of("/private/control.sock"), 0, 1000, "*")); }
    private void exchangeFixture(UnixManagedHostingProvisioner adapter, Map<String, Object> observed) throws Exception {
        doAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            return "prepare".equals(request.get("method")) ? frame("preparedResponse") : observed;
        }).when(adapter).exchange(anyMap());
    }
    @Test void frozenFlatWireAndBareManifestDigestAreByteCompatibleWithoutAnyCredential() {
        var p = preparation(); var c = candidate(); assertNotNull(c);
        assertEquals(frame("prepareRequest"), JSON.convertValue(UnixManagedHostingProvisioner.request(p, "prepare", null), new TypeReference<Map<String,Object>>() {}));
        assertEquals(frame("ensureRequest"), JSON.convertValue(UnixManagedHostingProvisioner.request(p, "ensure", c), new TypeReference<Map<String,Object>>() {}));
        assertEquals(frame("observeRequest"), JSON.convertValue(UnixManagedHostingProvisioner.request(p, "observe", c), new TypeReference<Map<String,Object>>() {}));
        assertFalse(c.manifestSha256().startsWith("sha256:"));
        var free = preparation("reprovisionPrepareRequest");
        assertEquals(frame("reprovisionPrepareRequest"), JSON.convertValue(UnixManagedHostingProvisioner.request(free, "prepare", null), new TypeReference<Map<String,Object>>() {}));
        assertEquals(2, UnixManagedHostingProvisioner.decodePrepared(free, frame("reprovisionPreparedResponse")).provisionGeneration());
    }
    @Test void preparedRejectsCrossWireScopeMethodNumericCoercionAndSecretsBeforeAuthorization() {
        for (var change : Map.<String,Object>of("ownerJiacn", "other", "method", "observe", "manifestSha256", "c".repeat(64),
                "reservedAt", "1791594000000", "provisionGeneration", 0, "enrollmentExpiresAt", 1.5, "operationKind", "REPROVISION").entrySet()) {
            var invalid = frame("preparedResponse"); invalid.put(change.getKey(), change.getValue());
            assertNull(UnixManagedHostingProvisioner.decodePrepared(preparation(), invalid), change.getKey());
        }
        var secret = frame("preparedResponse"); secret.put("enrollmentSecret", "forbidden-fixture");
        assertNull(UnixManagedHostingProvisioner.decodePrepared(preparation(), secret));
        var prefixed = frame("preparedResponse"); prefixed.put("manifestSha256", "sha256:" + prefixed.get("manifestSha256"));
        assertNull(UnixManagedHostingProvisioner.decodePrepared(preparation(), prefixed));
    }
    @Test void exactCurrentOperationRegistrationExecutorAndDurableReadinessAreAllRequired() {
        assertEquals(ManagedHostingProvisioner.Outcome.SERVICE_READY, UnixManagedHostingProvisioner.decode(preparation(), candidate(), "observe", frame("readyResponse")).outcome());
        for (var change : Map.<String,Object>of("canonicalAgentId", "other", "initialIntentId", "old", "bindingId", "18", "operationId", "old",
                "serviceReadyAt", "1791594002000", "registeredAt", 1791593999999L, "executorReady", false,
                "durableReady", false, "provisionGeneration", 2).entrySet()) {
            var invalid = frame("readyResponse"); invalid.put(change.getKey(), change.getValue());
            assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, UnixManagedHostingProvisioner.decode(preparation(), candidate(), "observe", invalid).outcome(), change.getKey());
        }
        for (String extra : List.of("apiKey", "engineThreadId", "profileRef", "commands")) {
            var invalid = frame("readyResponse"); invalid.put(extra, "forbidden");
            assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, UnixManagedHostingProvisioner.decode(preparation(), candidate(), "observe", invalid).outcome());
        }
    }
    @Test void prepareAuthorizationCommitPrecedesObserveAndNoEnsureNeededForLiveExactReadyProof() throws Exception {
        var adapter = adapter(); exchangeFixture(adapter, frame("readyResponse"));
        assertEquals(ManagedHostingProvisioner.Outcome.SERVICE_READY, adapter.prepareAndObserve(preparation()).outcome());
        var order = inOrder(adapter, credentials, sessions);
        order.verify(adapter).exchange(argThat(r -> "prepare".equals(r.get("method"))));
        order.verify(credentials).ensureInstallation(preparation(), candidate());
        order.verify(adapter).exchange(argThat(r -> "observe".equals(r.get("method"))));
        order.verify(sessions).currentRegisteredProof("0", "fixture-client", "agt_fixture");
        verify(adapter, never()).exchange(argThat(r -> "ensure".equals(r.get("method"))));
    }
    @Test void unknownObserveUsesSamePreparedInstallationAndGenerationForEnsure() throws Exception {
        var adapter = adapter(); List<Map<String,Object>> requests = new ArrayList<>();
        doAnswer(call -> {
            Map<String,Object> request = call.getArgument(0); requests.add(new LinkedHashMap<>(request));
            if ("prepare".equals(request.get("method"))) return frame("preparedResponse");
            if ("observe".equals(request.get("method"))) return frame("unknownResponse");
            var ready = frame("readyResponse"); ready.put("method", "ensure"); return ready;
        }).when(adapter).exchange(anyMap());
        assertEquals(ManagedHostingProvisioner.Outcome.SERVICE_READY, adapter.prepareAndObserve(preparation()).outcome());
        assertEquals(List.of("prepare", "observe", "ensure"), requests.stream().map(r -> r.get("method")).toList());
        assertTrue(requests.stream().noneMatch(r -> r.containsKey("apiKey") || r.containsKey("enrollmentSecret")));
        assertEquals(requests.get(1).get("installationId"), requests.get(2).get("installationId"));
        assertEquals(requests.get(1).get("provisionGeneration"), requests.get(2).get("provisionGeneration"));
    }
    @Test void runtimeReadyCannotReplaceApiCurrentScopedRegisteredProof() throws Exception {
        var adapter = adapter(); exchangeFixture(adapter, frame("readyResponse"));
        for (var invalid : List.of(proof("other-owner", "fixture-host", "fixture-instance", 2),
                proof("fixture-owner", "old-host", "fixture-instance", 2), proof("fixture-owner", "fixture-host", "old-instance", 2),
                proof("fixture-owner", "fixture-host", "fixture-instance", 1))) {
            when(sessions.currentRegisteredProof(anyString(), anyString(), anyString())).thenReturn(invalid);
            assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, adapter.prepareAndObserve(preparation()).outcome());
        }
        when(sessions.currentRegisteredProof(anyString(), anyString(), anyString())).thenThrow(new IllegalArgumentException("offline"));
        assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, adapter.prepareAndObserve(preparation()).outcome());
    }
    @Test void lostPrepareOrEnsureResponseAndRecoveryRequiredNeverRefundOrReenroll() throws Exception {
        var adapter = adapter(); doThrow(new java.net.SocketTimeoutException("fixture")).when(adapter).exchange(anyMap());
        assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, adapter.prepareAndObserve(preparation()).outcome());
        verify(credentials, never()).ensureInstallation(any(), any());
        var recovery = frame("unknownResponse"); recovery.put("outcome", "RECOVERY_REQUIRED"); exchangeFixture(adapter, recovery);
        assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, adapter.prepareAndObserve(preparation()).outcome());
        verify(adapter, never()).exchange(argThat(r -> "ensure".equals(r.get("method"))));
        var noEffect = frame("readyResponse"); noEffect.put("outcome", "FAILED_NO_EFFECT");
        assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, UnixManagedHostingProvisioner.decode(preparation(), candidate(), "observe", noEffect).outcome());
    }
    @Test void authorizationFailurePreventsAnyActivatingEnsure() throws Exception {
        var adapter = adapter(); exchangeFixture(adapter, frame("readyResponse"));
        doThrow(new IllegalStateException("CAS")).when(credentials).ensureInstallation(any(), any());
        assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, adapter.prepareAndObserve(preparation()).outcome());
        verify(adapter, never()).exchange(argThat(r -> !"prepare".equals(r.get("method"))));
    }
    @Test void liveCapabilitiesProbeIsTransactionExternalExactScopedAndFailClosed() throws Exception {
        var adapter = adapter();
        doAnswer(call -> { var response = new LinkedHashMap<String,Object>(call.getArgument(0)); response.put("hostId", "fixture-host"); response.put("available", true); return response; }).when(adapter).exchange(anyMap());
        assertTrue(adapter.probeCapabilities("0", "fixture-client", "fixture-owner"));
        assertFalse(adapter.probeCapabilities("other", "fixture-client", "fixture-owner"));
        assertFalse(adapter.probeCapabilities("0", "fixture-client", "*"));
        doThrow(new java.net.SocketTimeoutException("absent")).when(adapter).exchange(anyMap());
        assertFalse(adapter.probeCapabilities("0", "fixture-client", "fixture-owner"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> adapter.probeCapabilities("0", "fixture-client", "fixture-owner"));
            assertThrows(IllegalStateException.class, () -> adapter.prepareAndObserve(preparation()));
        } finally { TransactionSynchronizationManager.clear(); }
    }
    @Test @EnabledOnOs(OS.LINUX)
    void privateUnixExchangeSupportsExplicitRestrictedGroupAndRejectsWrongGroupOrWorldAccess(@TempDir Path root) throws Exception {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-x---"));
        Path socket = root.resolve("control.sock"); long uid = ((Number)Files.getAttribute(root, "unix:uid")).longValue();
        long gid = ((Number)Files.getAttribute(root, "unix:gid")).longValue();
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket)); Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw----"));
            UnixManagedHostingProvisioner.validateSocket(socket, uid, gid);
            assertThrows(java.io.IOException.class, () -> UnixManagedHostingProvisioner.validateSocket(socket, uid, gid + 1));
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var served = executor.submit(() -> {
                    try (var channel = server.accept()) {
                        ByteBuffer input = ByteBuffer.allocate(16384);
                        while (input.position() == 0 || input.get(input.position()-1) != '\n') {
                            if (channel.read(input) < 0) throw new java.io.IOException("fixture EOF");
                        }
                        input.flip(); byte[] data = new byte[input.remaining()]; input.get(data);
                        assertFalse(new String(data, java.nio.charset.StandardCharsets.UTF_8).contains("apiKey"));
                        var output = ByteBuffer.wrap((JSON.writeValueAsString(frame("readyResponse")) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        while (output.hasRemaining()) channel.write(output); return true;
                    }
                });
                assertEquals(frame("readyResponse"), adapter(socket, uid, gid, "fixture-owner").exchange(UnixManagedHostingProvisioner.request(preparation(), "observe", candidate())));
                assertTrue(served.get(5, TimeUnit.SECONDS));
            }
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw-rw-"));
            assertThrows(java.io.IOException.class, () -> UnixManagedHostingProvisioner.validateSocket(socket, uid, gid));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw----"));
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwx---"));
            assertThrows(java.io.IOException.class, () -> UnixManagedHostingProvisioner.validateSocket(socket, uid, gid));
        }
    }
    @Test @EnabledOnOs(OS.LINUX)
    void duplicateJsonFieldsAreRejectedByActualSocketParser(@TempDir Path root) throws Exception {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path socket = root.resolve("control.sock"); long uid = ((Number)Files.getAttribute(root, "unix:uid")).longValue();
        long gid = ((Number)Files.getAttribute(root, "unix:gid")).longValue();
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket)); Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var served = executor.submit(() -> {
                    try (var channel = server.accept()) {
                        ByteBuffer input = ByteBuffer.allocate(16384);
                        while (input.position() == 0 || input.get(input.position()-1) != '\n') { if (channel.read(input) < 0) break; }
                        var bytes = ByteBuffer.wrap("{\"protocol\":\"runtime-hosting-v1\",\"protocol\":\"other\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        while (bytes.hasRemaining()) channel.write(bytes); return true;
                    }
                });
                assertThrows(Exception.class, () -> adapter(socket, uid, gid, "fixture-owner").exchange(UnixManagedHostingProvisioner.request(preparation(), "prepare", null)));
                assertTrue(served.get(5, TimeUnit.SECONDS));
            }
        }
    }
}
