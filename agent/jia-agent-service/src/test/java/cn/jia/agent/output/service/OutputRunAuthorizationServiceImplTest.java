package cn.jia.agent.output.service;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunRequest;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.output.OutputSourceAuthorizer;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.dao.OutputAccessTicketDao;
import cn.jia.agent.output.dao.OutputRunBindingDao;
import cn.jia.agent.output.dao.OutputSourceBindingDao;
import cn.jia.agent.output.dto.OutputAuthReceiptDTO;
import cn.jia.agent.output.dto.OutputContextDTO;
import cn.jia.agent.output.entity.OutputAccessTicketEntity;
import cn.jia.agent.output.entity.OutputRunBindingEntity;
import cn.jia.agent.output.entity.OutputSourceBindingEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.core.util.JsonUtil;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutputRunAuthorizationServiceImplTest extends BaseMockTest {
    @Mock OutputSourceAuthorizer sourceAuthorizer;
    @Mock OutputSourceBindingDao sourceDao;
    @Mock OutputRunBindingDao runDao;
    @Mock OutputAccessTicketDao ticketDao;
    @Mock AgentRuntimeDao runtimeDao;
    @Mock AgentIdentityService identityService;

    OutputRunAuthorizationServiceImpl service;
    OutputSourceBindingEntity source;
    AgentRuntimeEntity runtime;
    long now;

    @BeforeEach
    void setUp() {
        now = System.currentTimeMillis();
        when(sourceAuthorizer.sourceType()).thenReturn(OutputConstants.SOURCE_TASK);
        lenient().when(sourceAuthorizer.lockAndAuthorize(
                "owner", "client", "task-1", "agent-1"))
                .thenReturn(new OutputSourceAuthorization(
                        "owner", "client", OutputConstants.SOURCE_TASK,
                        "task-1", "owner", "agent-1", true));
        service = new OutputRunAuthorizationServiceImpl(
                new OutputSourceAuthorizerRegistry(List.of(sourceAuthorizer)),
                sourceDao, runDao, ticketDao, runtimeDao, identityService, true);

        source = source();
        runtime = runtime(7L, "runtime-current", true);
        lenient().when(sourceDao.findExact(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1", true))
                .thenReturn(source);
        lenient().when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", false))
                .thenReturn(runtime);
        lenient().when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true))
                .thenReturn(runtime);
        lenient().when(identityService.lockActiveCanonicalAgentIdsInScope(
                "owner", "client", "owner", List.of("agent-1")))
                .thenReturn(List.of("agent-1"));
        org.mockito.Mockito.lenient().when(identityService.requireActiveIdentityForBinding(
                "owner", "client", "owner", 7L, "agent-1"))
                .thenReturn(new AgentIdentityRegistryEntity());
    }

    @Test
    void redeliveryReusesActiveRunAndLocksInCanonicalOrder() {
        OutputRunBindingEntity prior = activeRun("00000000000000000000000000000001");
        when(runDao.findExactByOrigin(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1",
                "agent-1", "COMMAND", "command-1"))
                .thenReturn(prior);

        OutputContextDTO context = service.createOrRecoverRun(request()).orElseThrow();

        assertEquals(prior.getRunId(), context.runId());
        assertEquals(List.of(OutputConstants.CAPABILITY_HTTP_V1,
                OutputConstants.CAPABILITY_OWNER_SHARE_V1), context.capabilities());
        verify(runDao, never()).insert(any());
        InOrder order = inOrder(sourceAuthorizer, sourceDao, runDao, identityService, runtimeDao);
        order.verify(sourceAuthorizer).lockAndAuthorize(
                "owner", "client", "task-1", "agent-1");
        order.verify(sourceDao).findExact(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1", true);
        order.verify(runDao).findExactByOrigin(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1",
                "agent-1", "COMMAND", "command-1");
        order.verify(runtimeDao).findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", false);
        order.verify(identityService).lockActiveCanonicalAgentIdsInScope(
                "owner", "client", "owner", List.of("agent-1"));
        order.verify(runtimeDao).findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true);
    }

    @Test
    void terminalOrExpiredOriginCannotSilentlyCreateAnotherRun() {
        OutputRunBindingEntity terminal = activeRun("00000000000000000000000000000002");
        terminal.setState(OutputConstants.RUN_CLOSED);
        when(runDao.findExactByOrigin(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1",
                "agent-1", "COMMAND", "command-1"))
                .thenReturn(terminal);

        OutputAuthorizationException failure = assertThrows(
                OutputAuthorizationException.class,
                () -> service.createOrRecoverRun(request()));
        assertEquals("OUTPUT_RUN_REDISPATCH_REQUIRED", failure.getCode());
        verify(runDao, never()).insert(any());
    }

    @Test
    void policyZeroWithoutFreshCapabilityKeepsLegacyWireAndCreatesNoRun() {
        AgentRuntimeEntity legacyRuntime = runtime(7L, null, false);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", false))
                .thenReturn(legacyRuntime);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true))
                .thenReturn(legacyRuntime);

        assertTrue(service.createOrRecoverRun(request()).isEmpty());

        verify(runDao, never()).insert(any());
    }

    @Test
    void multiTargetBatchLocksEveryRunBeforeSortedIdentityAndRuntimeRows() {
        OutputRunRequest first = request();
        OutputRunRequest second = new OutputRunRequest(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1",
                "agent-2", "COMMAND", "command-2", null, 0);
        AgentRuntimeEntity runtimeTwo = runtime(8L, "runtime-2", true);
        runtimeTwo.setAgentId("agent-2");
        lenient().when(sourceAuthorizer.lockAndAuthorize(
                "owner", "client", "task-1", "agent-2"))
                .thenReturn(new OutputSourceAuthorization(
                        "owner", "client", OutputConstants.SOURCE_TASK,
                        "task-1", "owner", "agent-2", true));
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-2", false)).thenReturn(runtimeTwo);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-2", true)).thenReturn(runtimeTwo);
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                "owner", "client", "owner", List.of("agent-1", "agent-2")))
                .thenReturn(List.of("agent-1", "agent-2"));
        when(identityService.requireActiveIdentityForBinding(
                "owner", "client", "owner", 8L, "agent-2"))
                .thenReturn(new AgentIdentityRegistryEntity());
        when(runDao.insert(any())).thenReturn(1);

        Map<String, OutputContextDTO> contexts = service.createOrRecoverRuns(
                List.of(second, first), List.of("agent-2", "agent-1"));

        assertEquals(List.of("agent-1", "agent-2"), contexts.keySet().stream().sorted().toList());
        InOrder order = inOrder(sourceAuthorizer, sourceDao, runDao, runtimeDao, identityService);
        order.verify(sourceAuthorizer).lockAndAuthorize(
                "owner", "client", "task-1", "agent-1");
        order.verify(sourceAuthorizer).lockAndAuthorize(
                "owner", "client", "task-1", "agent-2");
        order.verify(sourceDao).findExact(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1", true);
        order.verify(runDao).findExactByOrigin(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1",
                "agent-1", "COMMAND", "command-1");
        order.verify(runDao).findExactByOrigin(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1",
                "agent-2", "COMMAND", "command-2");
        order.verify(runtimeDao).findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", false);
        order.verify(runtimeDao).findExactOutputRuntime(
                "owner", "client", "owner", "agent-2", false);
        order.verify(identityService).lockActiveCanonicalAgentIdsInScope(
                "owner", "client", "owner", List.of("agent-1", "agent-2"));
        order.verify(runtimeDao).findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true);
        order.verify(runtimeDao).findExactOutputRuntime(
                "owner", "client", "owner", "agent-2", true);
    }

    @Test
    void issueStoresOnlySha256AndExactBearerBootstrapsPersistedScope() throws Exception {
        String runId = "00000000000000000000000000000003";
        OutputRunBindingEntity run = activeRun(runId);
        when(runDao.findExactByRun("owner", "client", runId, false)).thenReturn(run);
        when(runDao.findExactByRun("owner", "client", runId, true)).thenReturn(run);
        when(ticketDao.lockRecentHashesForBinding(
                eq("owner"), eq("client"), eq("7"), anyLong()))
                .thenReturn(java.util.Collections.nCopies(59, new byte[32]));
        when(ticketDao.insert(any())).thenReturn(1);

        OutputAuthReceiptDTO receipt = service.issueTicket(
                "owner", "client", "agent-1", "runtime-current", "auth-1", runId);

        ArgumentCaptor<OutputAccessTicketEntity> persisted =
                ArgumentCaptor.forClass(OutputAccessTicketEntity.class);
        verify(ticketDao).insert(persisted.capture());
        byte[] expectedHash = MessageDigest.getInstance("SHA-256")
                .digest(receipt.token().getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(expectedHash, persisted.getValue().getTicketHash());
        assertFalse(Arrays.equals(persisted.getValue().getTicketHash(),
                receipt.token().getBytes(StandardCharsets.US_ASCII)));
        assertNotEquals(receipt.token(), persisted.getValue().getOperationsJson());

        AgentRuntimeEntity recoveredRuntime = runtime(7L, null, false);
        when(ticketDao.findByHash(
                argThat(hash -> Arrays.equals(hash, expectedHash)), eq(false)))
                .thenReturn(persisted.getValue());
        when(ticketDao.findByHash(
                argThat(hash -> Arrays.equals(hash, expectedHash)), eq(true)))
                .thenReturn(persisted.getValue());
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", false))
                .thenReturn(recoveredRuntime);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true))
                .thenReturn(recoveredRuntime);

        OutputTicketAuthorization authorization = service.authorizeTicket(
                receipt.token(), OutputConstants.OP_UPLOAD, false);

        assertEquals("owner", authorization.tenantId());
        assertEquals("client", authorization.clientId());
        assertEquals("7", authorization.bindingId());
        assertEquals("runtime-current", authorization.runtimeInstanceId());
        assertTrue(authorization.operations().contains(OutputConstants.OP_UPLOAD));

        String wrong = receipt.token().substring(0, receipt.token().length() - 1)
                + (receipt.token().endsWith("A") ? "B" : "A");
        assertThrows(OutputAuthorizationException.class,
                () -> service.authorizeTicket(wrong, OutputConstants.OP_UPLOAD, false));
    }

    @Test
    void currentRuntimeBindingMustMatchBothLockedIdentityAndPersistedRun() {
        String runId = "00000000000000000000000000000004";
        OutputRunBindingEntity run = activeRun(runId);
        when(runDao.findExactByRun("owner", "client", runId, false)).thenReturn(run);
        when(runDao.findExactByRun("owner", "client", runId, true)).thenReturn(run);
        AgentRuntimeEntity moved = runtime(8L, "runtime-current", true);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", false)).thenReturn(moved);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true)).thenReturn(moved);
        when(identityService.requireActiveIdentityForBinding(
                "owner", "client", "owner", 8L, "agent-1"))
                .thenReturn(new AgentIdentityRegistryEntity());

        assertThrows(OutputAuthorizationException.class,
                () -> service.issueTicket(
                        "owner", "client", "agent-1", "runtime-current", "auth-2", runId));
        verify(ticketDao, never()).insert(any());
    }

    @Test
    void currentGenerationCanRenewTicketAfterDispatchFreshnessWindow() {
        String runId = "00000000000000000000000000000008";
        OutputRunBindingEntity run = activeRun(runId);
        runtime.setOutputCapabilitiesUpdatedAt(
                now - OutputConstants.CAPABILITY_FRESHNESS_MILLIS - 1);
        when(runDao.findExactByRun("owner", "client", runId, false)).thenReturn(run);
        when(runDao.findExactByRun("owner", "client", runId, true)).thenReturn(run);
        when(ticketDao.lockRecentHashesForBinding(
                eq("owner"), eq("client"), eq("7"), anyLong())).thenReturn(List.of());
        when(ticketDao.insert(any())).thenReturn(1);

        OutputAuthReceiptDTO receipt = service.issueTicket(
                "owner", "client", "agent-1", "runtime-current", "auth-stale", runId);

        assertEquals(runId, receipt.runId());
        verify(ticketDao).insert(any());
    }

    @Test
    void dispatchUsesFreshCurrentGenerationRatherThanOriginalRuntimeAuditField() {
        String runId = "00000000000000000000000000000009";
        OutputRunBindingEntity run = activeRun(runId);
        run.setOriginalRuntimeId("runtime-original");
        AgentRuntimeEntity recovered = runtime(7L, "runtime-recovered", true);
        when(runDao.findExactByRun("owner", "client", runId, false)).thenReturn(run);
        when(runDao.findExactByRun("owner", "client", runId, true)).thenReturn(run);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", false)).thenReturn(recovered);
        when(runtimeDao.findExactOutputRuntime(
                "owner", "client", "owner", "agent-1", true)).thenReturn(recovered);

        assertEquals("runtime-recovered", service.requireFreshDispatchRuntime(
                "owner", "client", "agent-1", runId));

        recovered.setOutputCapabilitiesUpdatedAt(
                now - OutputConstants.CAPABILITY_FRESHNESS_MILLIS - 1);
        OutputAuthorizationException stale = assertThrows(
                OutputAuthorizationException.class,
                () -> service.requireFreshDispatchRuntime(
                        "owner", "client", "agent-1", runId));
        assertEquals("OUTPUT_DISPATCH_RUNTIME_UNAVAILABLE", stale.getCode());
    }

    @Test
    void terminalRunCanIssueOnlyStatusTicketThroughExplicitReceiptRead() {
        String runId = "00000000000000000000000000000005";
        OutputRunBindingEntity terminal = activeRun(runId);
        terminal.setState(OutputConstants.RUN_RESULT_SUBMITTED);
        when(runDao.findExactByRun("owner", "client", runId, false)).thenReturn(terminal);
        when(runDao.findExactByRun("owner", "client", runId, true)).thenReturn(terminal);
        when(sourceAuthorizer.lockAndAuthorize(
                "owner", "client", "task-1", "agent-1",
                OutputSourceAccessMode.RECEIPT_READ))
                .thenReturn(new OutputSourceAuthorization(
                        "owner", "client", OutputConstants.SOURCE_TASK,
                        "task-1", "owner", "agent-1", false));
        when(ticketDao.lockRecentHashesForBinding(
                eq("owner"), eq("client"), eq("7"), anyLong()))
                .thenReturn(List.of());
        when(ticketDao.insert(any())).thenReturn(1);

        OutputAuthReceiptDTO receipt = service.issueTicket(
                "owner", "client", "agent-1", "runtime-current", "auth-3", runId);

        assertEquals(List.of(OutputConstants.OP_STATUS), receipt.operations());
        ArgumentCaptor<OutputAccessTicketEntity> persisted =
                ArgumentCaptor.forClass(OutputAccessTicketEntity.class);
        verify(ticketDao).insert(persisted.capture());
        assertEquals(JsonUtil.toJson(List.of(OutputConstants.OP_STATUS)),
                persisted.getValue().getOperationsJson());
    }

    @Test
    void lockedRunRevocationOverridesActiveProjection() {
        String runId = "00000000000000000000000000000006";
        OutputRunBindingEntity projected = activeRun(runId);
        OutputRunBindingEntity revoked = activeRun(runId);
        revoked.setState(OutputConstants.RUN_REVOKED);
        when(runDao.findExactByRun("owner", "client", runId, false)).thenReturn(projected);
        when(runDao.findExactByRun("owner", "client", runId, true)).thenReturn(revoked);

        assertThrows(OutputAuthorizationException.class, () -> service.issueTicket(
                "owner", "client", "agent-1", "runtime-current", "auth-4", runId));

        verify(ticketDao, never()).insert(any());
    }

    @Test
    void lockedTicketRevocationOverridesBootstrapProjection() {
        String runId = "00000000000000000000000000000007";
        OutputRunBindingEntity run = activeRun(runId);
        OutputAccessTicketEntity projected = ticket(runId, null);
        OutputAccessTicketEntity revoked = ticket(runId, now);
        byte[] hash = projected.getTicketHash();
        when(ticketDao.findByHash(argThat(value -> Arrays.equals(hash, value)), eq(false)))
                .thenReturn(projected);
        when(ticketDao.findByHash(argThat(value -> Arrays.equals(hash, value)), eq(true)))
                .thenReturn(revoked);
        when(runDao.findExactByRun("owner", "client", runId, false)).thenReturn(run);
        when(runDao.findExactByRun("owner", "client", runId, true)).thenReturn(run);

        assertThrows(OutputAuthorizationException.class, () -> service.authorizeTicket(
                bearer(), OutputConstants.OP_UPLOAD, false));
    }

    private OutputRunRequest request() {
        return new OutputRunRequest(
                "owner", "client", OutputConstants.SOURCE_TASK, "task-1",
                "agent-1", "COMMAND", "command-1", null, 0);
    }

    private OutputSourceBindingEntity source() {
        OutputSourceBindingEntity value = new OutputSourceBindingEntity();
        value.setTenantId("owner");
        value.setClientId("client");
        value.setSourceType(OutputConstants.SOURCE_TASK);
        value.setSourceId("task-1");
        value.setOwnerJiacn("owner");
        value.setOwnershipState(OutputConstants.OWNERSHIP_ACTIVE);
        return value;
    }

    private OutputRunBindingEntity activeRun(String runId) {
        OutputRunBindingEntity value = new OutputRunBindingEntity();
        value.setTenantId("owner");
        value.setClientId("client");
        value.setRunId(runId);
        value.setSourceType(OutputConstants.SOURCE_TASK);
        value.setSourceId("task-1");
        value.setProducerAgentId("agent-1");
        value.setBindingId("7");
        value.setOriginalRuntimeId("runtime-current");
        value.setOriginType("COMMAND");
        value.setOriginId("command-1");
        value.setState(OutputConstants.RUN_ACTIVE);
        value.setPolicyVersion(0);
        value.setRecoveryUntil(now + OutputConstants.RUN_RECOVERY_MILLIS);
        value.setMaxBytes(OutputConstants.DEFAULT_MAX_RUN_BYTES);
        value.setMaxFiles(OutputConstants.DEFAULT_MAX_FILES);
        return value;
    }

    private AgentRuntimeEntity runtime(
            long bindingId, String runtimeId, boolean capabilities) {
        AgentRuntimeEntity value = new AgentRuntimeEntity();
        value.setTenantId("owner");
        value.setClientId("client");
        value.setOwnerJiacn("owner");
        value.setAgentId("agent-1");
        value.setBindingId(bindingId);
        value.setOutputCapabilitiesRuntimeId(runtimeId);
        if (capabilities) {
            value.setOutputCapabilitiesJson(JsonUtil.toJson(List.of(
                    OutputConstants.CAPABILITY_HTTP_V1,
                    OutputConstants.CAPABILITY_OWNER_SHARE_V1)));
            value.setOutputCapabilitiesUpdatedAt(now);
        }
        return value;
    }

    private OutputAccessTicketEntity ticket(String runId, Long revokedAt) {
        OutputAccessTicketEntity value = new OutputAccessTicketEntity();
        value.setTenantId("owner");
        value.setClientId("client");
        value.setTicketHash(sha256(bearer()));
        value.setRunId(runId);
        value.setBindingId("7");
        value.setIssuedRuntimeId("runtime-current");
        value.setOperationsJson(JsonUtil.toJson(OutputConstants.R1_TICKET_OPERATIONS));
        value.setExpiresAt(now + OutputConstants.TICKET_TTL_MILLIS);
        value.setRevokedAt(revokedAt);
        return value;
    }

    private String bearer() {
        return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
