package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentCommandV2RuntimeAuthority;
import cn.jia.agent.service.AgentCommandV2RuntimeGateway;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandV2ExecutionPortImplTest {
    private final WorkerScope scope = new WorkerScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "local-test", "consumer-a", 3, 5,
            7, "agent-command", 9);
    private final AgentCommandV2RuntimeAuthority authority =
            mock(AgentCommandV2RuntimeAuthority.class);
    private final AgentCommandV2RuntimeGateway gateway =
            mock(AgentCommandV2RuntimeGateway.class);
    private final AgentCommandV2ExecutionPortImpl port =
            new AgentCommandV2ExecutionPortImpl(scope, authority, gateway);

    @Test
    void sendsTheOriginalBytesAndPreservesStableTupleAcrossRedelivery() {
        byte[] wire = wire(41L, "owner-a", "1", "cyf", "local-test", 1);
        AgentCommandV2ExecutionPort.Command command = new AgentCommandV2ExecutionPort.Command(
                token(), "owner-a", wire);
        when(authority.authorizeAndDispatch(eq(scope), any(), eq(gateway)))
                .thenReturn(AgentCommandV2RuntimeGateway.DispatchResult.dispatched());

        assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED,
                port.execute(command).kind());
        assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED,
                port.execute(command).kind());

        ArgumentCaptor<AgentCommandV2ExecutionPort.Command> captured =
                ArgumentCaptor.forClass(AgentCommandV2ExecutionPort.Command.class);
        verify(authority, times(2)).authorizeAndDispatch(eq(scope), captured.capture(), eq(gateway));
        for (AgentCommandV2ExecutionPort.Command attempt : captured.getAllValues()) {
            assertEquals("msg-1", attempt.claimToken().messageId());
            assertEquals("cmd-1", attempt.claimToken().commandId());
            assertEquals(41L, attempt.claimToken().deliveryId());
            assertEquals(1, attempt.claimToken().activeAttempt());
            assertArrayEquals(wire, attempt.wirePayload());
        }
    }

    @Test
    void stableAgentLedgerDeduplicatesReissueAndRejectsSemanticDrift() {
        PersistentAgentLedgerAuthority ledger = new PersistentAgentLedgerAuthority();
        AgentCommandV2ExecutionPortImpl ledgerPort =
                new AgentCommandV2ExecutionPortImpl(scope, ledger, gateway);
        byte[] firstWire = wire(41L, "owner-a", "1", "cyf", "local-test",
                1, "msg-1", "{}");
        byte[] reissuedWire = wire(41L, "owner-a", "1", "cyf", "local-test",
                2, "msg-2", "{}");
        byte[] driftedWire = wire(41L, "owner-a", "1", "cyf", "local-test",
                3, "msg-3", "{\"changed\":true}");

        assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED, ledgerPort.execute(
                new AgentCommandV2ExecutionPort.Command(
                        token("msg-1", 1), "owner-a", firstWire)).kind());
        assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED, ledgerPort.execute(
                new AgentCommandV2ExecutionPort.Command(
                        token("msg-2", 2), "owner-a", reissuedWire)).kind());
        assertEquals(1, ledger.businessExecutions.get());

        assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED, ledgerPort.execute(
                new AgentCommandV2ExecutionPort.Command(
                        token("msg-3", 3), "owner-a", driftedWire)).kind());
        assertEquals(1, ledger.businessExecutions.get());
        assertFalse(AgentCommandV2RuntimeGateway.DEDUPLICATION_KEY_CONTRACT
                .contains("messageId"));
        assertFalse(AgentCommandV2RuntimeGateway.DEDUPLICATION_KEY_CONTRACT
                .contains("attempt"));
    }

    @Test
    void rejectsEveryWireOrClaimScopeConflictBeforeRuntimeLookup() {
        for (byte[] conflict : new byte[][] {
                wire(42L, "owner-a", "1", "cyf", "local-test", 1),
                wire(41L, "owner-b", "1", "cyf", "local-test", 1),
                wire(41L, "owner-a", "2", "cyf", "local-test", 1),
                wire(41L, "owner-a", "1", "other", "local-test", 1),
                wire(41L, "owner-a", "1", "cyf", "other-pool", 1),
                wire(41L, "owner-a", "1", "cyf", "local-test", 2)
        }) {
            assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,
                    port.execute(new AgentCommandV2ExecutionPort.Command(
                            token(), "owner-a", conflict)).kind());
        }
        verify(authority, never()).authorizeAndDispatch(any(), any(), any());
    }

    @Test
    void mapsOfflineAndSendFailureToBoundedRetryAndIdentityDenialToRejected() {
        AgentCommandV2ExecutionPort.Command command = new AgentCommandV2ExecutionPort.Command(
                token(), "owner-a", wire(41L, "owner-a", "1", "cyf", "local-test", 1));
        when(authority.authorizeAndDispatch(scope, command, gateway))
                .thenReturn(AgentCommandV2RuntimeGateway.DispatchResult.offline())
                .thenReturn(AgentCommandV2RuntimeGateway.DispatchResult.sendFailed())
                .thenReturn(AgentCommandV2RuntimeGateway.DispatchResult.rejected());
        assertEquals(5_000L, port.execute(command).retryAfterMillis());
        assertEquals(5_000L, port.execute(command).retryAfterMillis());
        assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED, port.execute(command).kind());
    }

    private AgentInboxClaimToken token() {
        return token("msg-1", 1);
    }

    private AgentInboxClaimToken token(String messageId, int attempt) {
        return new AgentInboxClaimToken(11L, "agent-command-dispatch-v2", "1", "cyf",
                "local-test", 7L, "producer-a", "agent-command", 9L, 2,
                "consumer-a", 3L, "client-a", messageId, "evt-" + attempt, "cmd-1",
                "task-1", "agent-1", "TASK_INVITE", 41L, "lease-a", 9_000L,
                attempt, 3L + attempt, attempt, 4L + attempt, 99_999L);
    }

    private byte[] wire(long deliveryId, String owner, String tenant, String domain,
            String pool, int attempt) {
        return wire(deliveryId, owner, tenant, domain, pool, attempt, "msg-1", "{}");
    }

    private byte[] wire(long deliveryId, String owner, String tenant, String domain,
            String pool, int attempt, String messageId, String payload) {
        return ("{\"schemaVersion\":2,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"" + messageId + "\",\"commandId\":\"cmd-1\"," +
                "\"deliveryId\":\"" + deliveryId + "\"," +
                "\"correlationId\":\"task-1\",\"causationId\":\"cause-1\"," +
                "\"tenantId\":\"" + tenant + "\",\"dataDomainId\":\"" + domain + "\"," +
                "\"executionPoolId\":\"" + pool + "\"," +
                "\"producerDeploymentId\":\"producer-a\",\"admissionEpoch\":7," +
                "\"moduleId\":\"agent-command\",\"migrationEpoch\":9," +
                "\"clientId\":\"client-a\",\"ownerJiacn\":\"" + owner + "\"," +
                "\"taskId\":\"task-1\",\"workItemId\":null," +
                "\"targetAgentId\":\"agent-1\",\"commandType\":\"TASK_INVITE\"," +
                "\"issuedAt\":1000,\"expiresAt\":99999,\"attempt\":" + attempt + "," +
                "\"payload\":" + payload + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final class PersistentAgentLedgerAuthority
            implements AgentCommandV2RuntimeAuthority {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Map<StableKey, LedgerEntry> entries = new ConcurrentHashMap<>();
        private final AtomicInteger businessExecutions = new AtomicInteger();

        @Override
        public AgentCommandV2RuntimeGateway.DispatchResult authorizeAndDispatch(
                WorkerScope workerScope, AgentCommandV2ExecutionPort.Command command,
                AgentCommandV2RuntimeGateway gateway) {
            AgentInboxClaimToken token = command.claimToken();
            StableKey key = new StableKey(token.tenantId(), token.dataDomainId(),
                    token.executionPoolId(), token.commandId(), token.deliveryId());
            byte[] fingerprint = semanticFingerprint(command.wirePayload());
            LedgerEntry existing = entries.get(key);
            if (existing != null) {
                return MessageDigest.isEqual(existing.fingerprint(), fingerprint)
                        ? existing.outcome()
                        : AgentCommandV2RuntimeGateway.DispatchResult.rejected();
            }
            AgentCommandV2RuntimeGateway.DispatchResult outcome =
                    AgentCommandV2RuntimeGateway.DispatchResult.dispatched();
            LedgerEntry winner = entries.putIfAbsent(key, new LedgerEntry(fingerprint, outcome));
            if (winner != null) {
                return MessageDigest.isEqual(winner.fingerprint(), fingerprint)
                        ? winner.outcome()
                        : AgentCommandV2RuntimeGateway.DispatchResult.rejected();
            }
            businessExecutions.incrementAndGet();
            return outcome;
        }

        private static byte[] semanticFingerprint(byte[] wirePayload) {
            try {
                JsonNode root = JSON.readTree(wirePayload);
                String projection = String.join("\0",
                        root.get("commandId").asText(),
                        root.get("correlationId").asText(),
                        root.get("causationId").asText(),
                        root.get("tenantId").asText(),
                        root.get("clientId").asText(),
                        root.get("ownerJiacn").asText(),
                        root.get("taskId").asText(),
                        root.get("workItemId").toString(),
                        root.get("targetAgentId").asText(),
                        root.get("commandType").asText(),
                        root.get("issuedAt").toString(),
                        root.get("expiresAt").toString(),
                        root.get("payload").toString());
                return AgentCommandAmqpContract.sha256(
                        projection.getBytes(StandardCharsets.UTF_8));
            } catch (Exception invalid) {
                return new byte[0];
            }
        }
    }

    private record StableKey(String tenantId, String dataDomainId,
            String executionPoolId, String commandId, long deliveryId) {
    }

    private record LedgerEntry(byte[] fingerprint,
            AgentCommandV2RuntimeGateway.DispatchResult outcome) {
    }
}
