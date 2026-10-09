package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.HallRequestDraftDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HallFormalLegacyReportGuardTest extends BaseMockTest {
    @Mock AgentTaskMetaDao roots;
    @Mock AgentTaskMemberDao members;
    @Mock AgentTaskWorkItemDao items;
    @Mock AgentTaskAggregationService aggregation;
    @Mock AgentIdentityService identities;
    @Mock AgentTaskMutationTransaction transaction;
    @Mock AgentTaskEventWriter events;
    @Mock HallRequestDraftDao drafts;
    @Mock ObjectProvider<HallRequestDraftDao> provider;

    private AgentLegacyTaskCompatibilityService adapter;
    private AgentTaskMetaEntity root;

    @BeforeEach void setup() {
        root = new AgentTaskMetaEntity().setTaskId("396").setTaskVersion(1L)
                .setRewardStatus("assigned");
        root.setTenantId("0"); root.setClientId("client-a"); root.setOwnerJiacn("owner-a");
        adapter = new AgentLegacyTaskCompatibilityService(roots, members, items,
                aggregation, identities, transaction, events, () -> 1_000L);
        when(transaction.executeWithLockedTaskRootInOwnerScope(
                eq("0"), eq("client-a"), eq("owner-a"), eq("396"), any()))
                .thenAnswer(inv -> ((AgentTaskMutationTransaction.LockedTaskMutation<?>) inv.getArgument(4))
                        .apply(root));
    }

    @Test void submittedFormalTaskRejectsRunningCompletedAndFailedBeforeAnyWrites() {
        when(provider.getIfAvailable()).thenReturn(drafts);
        adapter.configureHallDraftGuard(provider);
        when(drafts.countSubmittedTaskCreates("0", "client-a", "owner-a", "396")).thenReturn(1);
        for (String status : new String[] {"running", "completed", "failed"}) {
            AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                    () -> adapter.reportResolved("0", "client-a", "owner-a", "396",
                            "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", status, "failure"));
            assertEquals(AgentTaskCollaborationException.Reason.RESERVED_FOR_LEASE_PROTOCOL,
                    error.getReason());
        }
        verify(drafts, times(3)).countSubmittedTaskCreates("0", "client-a", "owner-a", "396");
        verifyNoInteractions(members, items, aggregation, events, identities);
    }

    @Test void missingOriginProofIsFailClosedWithoutWrites() {
        adapter.configureHallDraftGuard(provider);
        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> adapter.reportResolved("0", "client-a", "owner-a", "396",
                        "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "completed", null));
        assertEquals(AgentTaskCollaborationException.Reason.RESERVED_FOR_LEASE_PROTOCOL, error.getReason());
        verifyNoInteractions(members, items, aggregation, events, identities);
    }

    @Test void ambiguousFormalOriginsFailClosedWithoutWrites() {
        when(provider.getIfAvailable()).thenReturn(drafts);
        adapter.configureHallDraftGuard(provider);
        when(drafts.countSubmittedTaskCreates("0", "client-a", "owner-a", "396")).thenReturn(2);
        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> adapter.reportResolved("0", "client-a", "owner-a", "396",
                        "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "completed", null));
        assertEquals(AgentTaskCollaborationException.Reason.INVALID_PERSISTED_STATE, error.getReason());
        verifyNoInteractions(members, items, aggregation, events, identities);
    }

    @Test void ordinaryLegacyTaskProceedsPastOriginProofToIdentityChecks() {
        when(provider.getIfAvailable()).thenReturn(drafts);
        adapter.configureHallDraftGuard(provider);
        when(drafts.countSubmittedTaskCreates("0", "client-a", "owner-a", "396")).thenReturn(0);
        assertThrows(AgentTaskCollaborationException.class,
                () -> adapter.reportResolved("0", "client-a", "owner-a", "396",
                        "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "completed", null));
        verify(identities).lockActiveCanonicalAgentIdsInScope(eq("0"), eq("client-a"),
                eq("owner-a"), any());
    }
}
