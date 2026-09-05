package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.ManagedHostingAdapterProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UnixManagedHostingProvisionerTest {
    private final ManagedHostingCredentials credentials = mock(ManagedHostingCredentials.class);
    private static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    private ManagedHostingProvisioner.Preparation preparation() {
        return new ManagedHostingProvisioner.Preparation("Tenant-A", "Client-A", "Tenant-A", AGENT,
                "hri_00000000-0000-0000-0000-000000000001", "hrl_00000000-0000-0000-0000-000000000002", "17", System.currentTimeMillis()-10000);
    }
    private Map<String,Object> ready(ManagedHostingProvisioner.Preparation p) {
        var reply = UnixManagedHostingProvisioner.request(p, "fixture-only", "observe");
        reply.remove("apiKey"); reply.remove("method");
        reply.put("outcome", "SERVICE_READY"); reply.put("serviceReadyAt", Long.toString(p.reservedAt()+1));
        reply.put("profileRef", AGENT + "/" + p.intentId()); reply.put("runtimeInstanceId", "00000000-0000-0000-0000-000000000003");
        reply.put("engineThreadId", "thread-fixture"); reply.put("evidenceRef", "trusted-fixture-proof");
        return reply;
    }
    private UnixManagedHostingProvisioner adapter(Path socket, long uid, boolean enabled) {
        return new UnixManagedHostingProvisioner(new AgentHostingRentProperties(enabled, null, null, null),
                new ManagedHostingAdapterProperties(socket.toString(), uid, "Tenant-A", "Client-A", "Tenant-A", 2000), credentials);
    }
    @Test void exactEngineRegistrationProofAndCanonicalStringTimeAreRequired() {
        var p = preparation(); var valid = ready(p);
        assertEquals(ManagedHostingProvisioner.Outcome.SERVICE_READY, UnixManagedHostingProvisioner.decode(p, valid).outcome());
        for (var change : Map.<String,Object>of("agentId", "other", "intentId", "old", "bindingId", "18", "operationId", "old",
                "profileRef", "wrong", "runtimeInstanceId", AGENT, "serviceReadyAt", p.reservedAt()+1).entrySet()) {
            var wrong = new HashMap<>(valid); wrong.put(change.getKey(), change.getValue());
            assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, UnixManagedHostingProvisioner.decode(p, wrong).outcome(), change.getKey());
        }
        valid.remove("engineThreadId");
        assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, UnixManagedHostingProvisioner.decode(p, valid).outcome());
    }
    @Test void disabledAndCrossScopeCannotCreateKeysAndTransactionsCannotReachIo() {
        var adapter = adapter(Path.of("/private/host.sock"), 1, false);
        assertFalse(adapter.available()); assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, adapter.prepareAndObserve(preparation()).outcome());
        verifyNoInteractions(credentials);
        when(credentials.available()).thenReturn(true);
        var live = adapter(Path.of("/private/host.sock"), 1, true);
        assertTrue(live.availableFor("Tenant-A", "Client-A", "Tenant-A"));
        assertFalse(live.availableFor("tenant-a", "Client-A", "Tenant-A"));
        assertFalse(live.availableFor("Tenant-A", "Client-A", "wrong-owner"));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> adapter.prepareAndObserve(preparation())); }
        finally { TransactionSynchronizationManager.clear(); }
    }
    @Test void observeBeforeEnsureAndTimeoutNeverBecomeRefundableFailure() throws Exception {
        var p = preparation(); var adapter = spy(adapter(Path.of("/private/host.sock"), 1, true));
        when(credentials.available()).thenReturn(true); when(credentials.credential(p)).thenReturn("fixture-key");
        doReturn(ready(p)).when(adapter).exchange(anyMap());
        assertEquals(ManagedHostingProvisioner.Outcome.SERVICE_READY, adapter.prepareAndObserve(p).outcome());
        verify(adapter).exchange(argThat(request -> "observe".equals(request.get("method"))));
        doThrow(new java.net.SocketTimeoutException("fixture")).when(adapter).exchange(anyMap());
        assertEquals(ManagedHostingProvisioner.Outcome.UNKNOWN, adapter.prepareAndObserve(p).outcome());
    }
    @Test @EnabledOnOs(OS.LINUX)
    void actualPrivateUnixExchangeRejectsUnsafePermissionsAndReadsOneBoundedFrame(@TempDir Path root) throws Exception {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path socket = root.resolve("h.sock");
        long uid = ((Number) Files.getAttribute(root, "unix:uid")).longValue();
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            var p = preparation(); var proof = ready(p);
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            try {
                var served = executor.submit(() -> {
                    try (var channel = server.accept()) {
                        ByteBuffer input = ByteBuffer.allocate(16384);
                        while (true) { int count = channel.read(input); if (count < 0) throw new java.io.IOException("fixture EOF");
                            if (input.position() > 0 && input.get(input.position()-1) == '\n') break; }
                        input.flip(); var data = new byte[input.remaining()]; input.get(data);
                        assertTrue(new String(data, java.nio.charset.StandardCharsets.UTF_8).contains("\"method\":\"observe\""));
                        ByteBuffer output = ByteBuffer.wrap((JsonMapper.builder().build().writeValueAsString(proof)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        while (output.hasRemaining()) channel.write(output);
                        return true;
                    }
                });
                assertEquals(proof, adapter(socket, uid, true).exchange(UnixManagedHostingProvisioner.request(p, "fixture", "observe")));
                assertTrue(served.get(5, java.util.concurrent.TimeUnit.SECONDS));
                Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw-rw-"));
                assertThrows(java.io.IOException.class, () -> UnixManagedHostingProvisioner.validateSocket(socket, uid));
            } finally {
                server.close();
                executor.shutdownNow();
            }
        }
    }
}
