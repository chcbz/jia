package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileResultDTO;
import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyBootstrapRelayTest {
    private final AgentTaskBountyBootstrapOutboxService outbox = mock(AgentTaskBountyBootstrapOutboxService.class);
    private final ChatBountyBootstrapAdmissionService admission = mock(ChatBountyBootstrapAdmissionService.class);
    private final ChatBountyBootstrapRelay relay = new ChatBountyBootstrapRelay(outbox, admission);
    private final AgentTaskBountyBootstrapClaimDTO claim = new AgentTaskBountyBootstrapClaimDTO(
            "bootstrap-1", "0", "client", "owner", "task-1", "action-1", 1L,
            "TASK_REQUIREMENT_REVISION_V1", 3L, "agent-1", "grant-1", 1L,
            "GENERATE_IMAGE", List.of(), "a".repeat(64), "worker", Long.MAX_VALUE, 1, 2);

    @Test void committedRequestIsAcknowledgedExactlyOnceWithoutDispatchOrProviderCall() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenReturn(new ChatBountyBootstrapAdmissionService.Admission(
                "10", 1L, "request-1", "42", "step-1", false));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "ADMITTED", 3,
                        "10", "request-1"));
        relay.pollOnce();
        var receipt = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        var scope = org.mockito.ArgumentCaptor.forClass(AgentTaskExecutionGrantService.Scope.class);
        verify(outbox).reconcile(scope.capture(), receipt.capture(), anyLong());
        assertEquals(new AgentTaskExecutionGrantService.Scope("0", "client", "owner"), scope.getValue());
        assertEquals(AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED, receipt.getValue().outcome());
        assertEquals("request-1", receipt.getValue().initialRequestId());
        assertEquals(2L, receipt.getValue().expectedOutboxVersion());
    }

    @Test void failedAdmissionIsNeverReconciledAsDeliveredOrGenerated() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenThrow(new IllegalStateException("Missing exact revision"));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "RETRY", 3,
                        null, null));
        assertThrows(IllegalStateException.class, relay::pollOnce);
        var receipt = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox).reconcile(any(), receipt.capture(), anyLong());
        assertEquals(AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE,
                receipt.getValue().outcome());
        assertNull(receipt.getValue().conversationId());
        assertNull(receipt.getValue().initialRequestId());
    }

    @Test void staleOrUnconfirmedAckDoesNotClaimSuccess() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenReturn(new ChatBountyBootstrapAdmissionService.Admission(
                "10", 1, "request-1", "42", "step-1", true));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "RETRY", 3,
                        null, null));
        assertThrows(IllegalStateException.class, relay::pollOnce);
    }
}
