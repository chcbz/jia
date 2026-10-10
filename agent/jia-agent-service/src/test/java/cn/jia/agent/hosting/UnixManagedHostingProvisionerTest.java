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
    private Map<String, Object> wire(Map<String, Object> request) {
        return JSON.readValue(JSON.writeValueAsString(request), new TypeReference<Map<String, Object>>() {});
    }
    @Test void frozenFlatWireAndBareManifestDigestAreByteCompatibleWithoutAnyCredential() {
        var p = preparation(); var c = candidate(); assertNotNull(c);
        assertEquals(frame("prepareRequest"), wire(UnixManagedHostingProvisioner.request(p, "prepare", null)));
        assertEquals(frame("ensureRequest"), wire(UnixManagedHostingProvisioner.request(p, "ensure", c)));
        assertEquals(frame("observeRequest"), wire(UnixManagedHostingProvisioner.request(p, "observe", c)));
        assertFalse(c.manifestSha256().startsWith("sha256:"));
        var free = preparation("reprovisionPrepareRequest");
        assertEquals(frame("reprovisionPrepareRequest"), wire(UnixManagedHostingProvisioner.request(free, "prepare", null)));
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
    /** Real Java transport/decoder -> Runtime UDS/control/journal. Native API and executor remain synthetic. */
    @Test @EnabledOnOs(OS.LINUX)
    @EnabledIfEnvironmentVariable(named = "CYF_GSS_RUNTIME_TEST_SOURCE", matches = ".+")
    void actualJavaAdapterDrivesRuntimeInitialReplayAndFreeNewSessionWithoutCredentialWire() throws Exception {
        Path source = Path.of(System.getenv("CYF_GSS_RUNTIME_TEST_SOURCE")).toRealPath();
        Path script = source.resolve("conf/cyf-agent-runtime-v1/test/hosting-java-bridge-fixture.mjs");
        String node = System.getenv("CYF_GSS_NODE_BIN");
        assertNotNull(node, "Explicit tested Node executable required; no version gate");
        assertTrue(Files.isRegularFile(script));
        Process child = new ProcessBuilder(node, script.toString()).directory(source.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
             var output = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line = executor.submit(output::readLine).get(10, TimeUnit.SECONDS);
            assertNotNull(line, "Runtime fixture must publish its owned socket");
            Map<String, Object> endpoint = JSON.readValue(line, new TypeReference<Map<String, Object>>() {});
            assertEquals(Set.of("socketPath", "uid", "gid", "hostId", "instanceId"), endpoint.keySet());
            assertEquals("fixture-host", endpoint.get("hostId")); assertEquals("fixture-instance-1", endpoint.get("instanceId"));
            var real = adapter(Path.of((String)endpoint.get("socketPath")), ((Number)endpoint.get("uid")).longValue(),
                    ((Number)endpoint.get("gid")).longValue(), "fixture-owner");
            var candidates = new java.util.concurrent.ConcurrentHashMap<String, ManagedHostingProvisioner.Candidate>();
            var expectedSession = new java.util.concurrent.atomic.AtomicLong(1);
            doAnswer(call -> {
                var p = (ManagedHostingProvisioner.Preparation)call.getArgument(0);
                var c = (ManagedHostingProvisioner.Candidate)call.getArgument(1);
                var previous = candidates.putIfAbsent(p.operationId(), c);
                if (previous != null) assertEquals(previous, c, "Exact operation must replay its original candidate");
                assertEquals(p.operationId().equals(p.intentId()) ? 1 : 2, c.provisionGeneration());
                return null;
            }).when(credentials).ensureInstallation(any(), any());
            // Independent expected API proof: known fixture host/instance + committed prepared
            // installation, and session floor fixed by the test, NEVER derived from READY JSON.
            when(sessions.currentRegisteredProof("0", "fixture-client", "agt_fixture")).thenAnswer(call -> {
                var c = candidates.get("hri_fixture");
                assertNotNull(c);
                return new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0", "fixture-client",
                        "fixture-owner", "agt_fixture", "fixture-instance-1"), c.installationId(), "fixture-host", expectedSession.get(), "a".repeat(64), 1, 0);
            });
            assertTrue(real.probeCapabilities("0", "fixture-client", "fixture-owner"));
            var initial = preparation();
            var ready = pollFixtureReady(real, initial);
            assertEquals(initial, ready.preparation());
            var original = candidates.get(initial.operationId()); assertNotNull(original);
            assertEquals(ready, real.prepareAndObserve(initial));
            var free = new ManagedHostingProvisioner.Preparation(initial.tenantId(), initial.clientId(), initial.ownerJiacn(), initial.agentId(),
                    initial.intentId(), initial.leaseId(), initial.bindingId(), initial.reservedAt(), "hrr_fixture_free",
                    initial.requestedAt(), initial.reservedAt() + 2592000000L); // same-ms request still requires NEW session
            expectedSession.set(2);
            var renewed = pollFixtureReady(real, free);
            assertEquals(free, renewed.preparation()); assertEquals(renewed, real.prepareAndObserve(free));
            var replacement = candidates.get(free.operationId());
            assertEquals(original.installationId(), replacement.installationId());
            assertEquals(original.manifestSha256(), replacement.manifestSha256());
            assertEquals(original.enrollmentSecretSha256(), replacement.enrollmentSecretSha256());
            assertEquals(original.enrollmentExpiresAt(), replacement.enrollmentExpiresAt());
            assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, real.prepareAndObserve(initial).outcome());
            expectedSession.set(1); // stale independent API registration cannot authenticate current free READY
            assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, real.prepareAndObserve(free).outcome());
        } finally {
            child.getOutputStream().close(); // EOF asks this fixture to close its own journal/socket/host/private roots
            if (!child.waitFor(10, TimeUnit.SECONDS)) {
                child.destroy();
                if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly(); // exact child only, never peer processes
                fail("Owned Runtime fixture failed graceful shutdown");
            }
            assertEquals(0, child.exitValue(), "Runtime fixture cleanup");
        }
    }
    private ManagedHostingProvisioner.Observation pollFixtureReady(UnixManagedHostingProvisioner adapter,
            ManagedHostingProvisioner.Preparation preparation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ManagedHostingProvisioner.Observation observation;
        do {
            observation = adapter.prepareAndObserve(preparation);
            if (observation.outcome() == ManagedHostingProvisioner.Outcome.SERVICE_READY) return observation;
            Thread.sleep(25); // bounded polling ONLY this fixture's asynchronous synthetic executor
        } while (System.nanoTime() < deadline);
        fail("Local Runtime fixture did not become ready for " + preparation.operationId());
        return observation;
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
