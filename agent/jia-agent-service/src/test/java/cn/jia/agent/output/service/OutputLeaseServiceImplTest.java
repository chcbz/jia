package cn.jia.agent.output.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputLeaseHttpResult;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dto.OutputLeaseRequestDTO;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutputLeaseServiceImplTest {
    private static final String RUN = "22222222222222222222222222222222";
    private static final String AGENT = "agt_11111111111111111111111111111111";
    private OutputRunAuthorizationService authorization;
    private AgentTaskMutationTransaction transactions;
    private AgentWorkItemLeaseService leases;
    private OutputUploadDao receipts;
    private OutputLeaseServiceImpl service;
    private OutputTicketAuthorization ticket;
    private AgentTaskMetaEntity root;

    @BeforeEach
    void setUp() {
        authorization = mock(OutputRunAuthorizationService.class);
        transactions = mock(AgentTaskMutationTransaction.class);
        leases = mock(AgentWorkItemLeaseService.class);
        receipts = mock(OutputUploadDao.class);
        service = new OutputLeaseServiceImpl(authorization, transactions, leases, receipts);
        ticket = new OutputTicketAuthorization(
                "owner", "client", RUN, OutputConstants.SOURCE_TASK, "task-1", AGENT,
                "7", "runtime-1", true, OutputConstants.R2_LEASE_TICKET_OPERATIONS,
                System.currentTimeMillis() + 60_000, OutputConstants.RUN_ACTIVE, 1, "work-1");
        root = new AgentTaskMetaEntity();
        root.setTenantId("owner"); root.setClientId("client"); root.setTaskId("task-1");
        root.setTaskVersion(0L); root.setCurrentEventVersion(0L); root.setDeliveryPolicyVersion(1);
        when(transactions.executeWithLockedTaskRoot(eq("owner"), eq("client"), eq("task-1"), any()))
                .thenAnswer(call -> ((AgentTaskMutationTransaction.LockedTaskMutation<?>)
                        call.getArgument(3)).apply(root));
        when(authorization.authorizeTicket("a".repeat(43), OutputConstants.OP_LEASE, false))
                .thenReturn(ticket);
    }

    @Test
    void successPersistsReceiptAfterAuthorizationAndLeaseMutation() {
        AgentWorkItemLeaseDTO lease = lease("claimed", "lease-secret", 1L);
        when(leases.mutateWithLockedTaskRoot(eq(root), eq("owner"), eq("client"),
                eq("task-1"), eq("work-1"), eq("claim"), any())).thenReturn(lease);
        when(receipts.insertReceipt(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq(200), anyString(), anyLong(), anyLong()))
                .thenReturn(1);

        OutputLeaseHttpResult result = service.mutate(ticket, "Bearer " + "a".repeat(43),
                "lease-example-claim-0001", "task-1", "work-1", "claim",
                new OutputLeaseRequestDTO(RUN, "0", null, "120000"), "request-1");

        assertEquals(200, result.status());
        assertFalse(result.responseJson().contains(AGENT));
        assertTrue(result.responseJson().contains("\"version\":\"1\""));
        assertTrue(result.responseJson().contains("\"leaseUntil\":\"123\""));
        InOrder order = inOrder(authorization, receipts, leases);
        order.verify(authorization).authorizeTicket("a".repeat(43), OutputConstants.OP_LEASE, false);
        order.verify(receipts).lockReceipt("owner", "client", "RUN", RUN,
                "lease.claim", "lease-example-claim-0001");
        order.verify(leases).mutateWithLockedTaskRoot(eq(root), eq("owner"), eq("client"),
                eq("task-1"), eq("work-1"), eq("claim"), any());
        order.verify(receipts).insertReceipt(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq(200), eq(result.responseJson()),
                anyLong(), anyLong());
    }

    @Test
    void exactReplayReturnsOriginalBodyAndDifferentBodyConflicts() {
        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        when(leases.mutateWithLockedTaskRoot(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(lease("claimed", "lease-secret", 1L));
        when(receipts.insertReceipt(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), hash.capture(), eq(200), anyString(), anyLong(), anyLong())).thenReturn(1);
        OutputLeaseRequestDTO request = new OutputLeaseRequestDTO(RUN, "0", null, "120000");
        OutputLeaseHttpResult first = service.mutate(ticket, "Bearer " + "a".repeat(43),
                "lease-example-claim-0001", "task-1", "work-1", "claim", request, "request-1");
        when(receipts.lockReceipt("owner", "client", "RUN", RUN,
                "lease.claim", "lease-example-claim-0001"))
                .thenReturn(new OutputUploadDao.Receipt(hash.getValue(), 200, first.responseJson()));

        OutputLeaseHttpResult replay = service.mutate(ticket, "Bearer " + "a".repeat(43),
                "lease-example-claim-0001", "task-1", "work-1", "claim", request, "request-2");
        OutputLeaseHttpResult conflict = service.mutate(ticket, "Bearer " + "a".repeat(43),
                "lease-example-claim-0001", "task-1", "work-1", "claim",
                new OutputLeaseRequestDTO(RUN, "0", null, "120001"), "request-3");

        assertEquals(first, replay);
        assertEquals(409, conflict.status());
        verify(leases, org.mockito.Mockito.times(1)).mutateWithLockedTaskRoot(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void recoveryDelegatesOnlyAfterFreshLeaseAuthorization() {
        when(leases.recoverWithLockedTaskRoot(eq(root), eq("owner"), eq("client"),
                eq("task-1"), eq("work-1"), any())).thenReturn(lease("running", "lease-secret", 4L));

        OutputLeaseHttpResult result = service.recover(ticket, "Bearer " + "a".repeat(43),
                "task-1", "work-1", "request-1");

        assertEquals(200, result.status());
        verify(authorization).authorizeTicket("a".repeat(43), OutputConstants.OP_LEASE, false);
        verify(receipts, never()).lockReceipt(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void ticketRunCannotAuthorizeAnotherRunBodyForAnyMutation() {
        String otherRun = "33333333333333333333333333333333";
        Map<String, OutputLeaseRequestDTO> requests = Map.of(
                "claim", new OutputLeaseRequestDTO(otherRun, "0", null, "120000"),
                "start", new OutputLeaseRequestDTO(otherRun, "1", "lease-secret", null),
                "heartbeat", new OutputLeaseRequestDTO(
                        otherRun, "2", "lease-secret", "40000"),
                "release", new OutputLeaseRequestDTO(otherRun, "3", "lease-secret", null));

        for (Map.Entry<String, OutputLeaseRequestDTO> entry : requests.entrySet()) {
            OutputAuthorizationException denied = assertThrows(
                    OutputAuthorizationException.class,
                    () -> service.mutate(ticket, "Bearer " + "a".repeat(43),
                            "lease-cross-run-" + entry.getKey(), "task-1", "work-1",
                            entry.getKey(), entry.getValue(), "request-" + entry.getKey()));
            assertEquals("OUTPUT_AUTH_FORBIDDEN", denied.getCode());
        }

        verifyNoInteractions(leases, receipts);
    }

    @Test
    void inactiveLeaseOmitsTokenAndExpiryInsteadOfSerializingNulls() {
        when(leases.mutateWithLockedTaskRoot(eq(root), eq("owner"), eq("client"),
                eq("task-1"), eq("work-1"), eq("release"), any()))
                .thenReturn(lease("ready", null, 8L));
        when(receipts.insertReceipt(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), eq(200), anyString(), anyLong(), anyLong()))
                .thenReturn(1);

        OutputLeaseHttpResult result = service.mutate(ticket, "Bearer " + "a".repeat(43),
                "lease-example-release-001", "task-1", "work-1", "release",
                new OutputLeaseRequestDTO(RUN, "7", "lease-secret", null), "request-release");

        assertEquals(200, result.status());
        assertTrue(result.responseJson().contains("\"status\":\"ready\""));
        assertTrue(result.responseJson().contains("\"version\":\"8\""));
        assertFalse(result.responseJson().contains("leaseToken"));
        assertFalse(result.responseJson().contains("leaseUntil"));
    }

    private AgentWorkItemLeaseDTO lease(String status, String token, long version) {
        AgentWorkItemLeaseDTO value = new AgentWorkItemLeaseDTO();
        value.setTaskId("task-1"); value.setWorkItemId("work-1"); value.setAgentId(AGENT);
        value.setRunId(RUN); value.setStatus(status); value.setLeaseToken(token);
        value.setLeaseUntil(token == null ? null : 123L); value.setAttemptCount(0);
        value.setMaxAttempts(3); value.setVersion(version); value.setChangedAt(100L);
        return value;
    }
}
