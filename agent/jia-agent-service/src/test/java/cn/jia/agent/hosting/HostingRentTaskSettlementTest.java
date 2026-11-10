package cn.jia.agent.hosting;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.*;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.EconomyScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingRentTaskSettlementTest {
    final EconomyScope scope = new EconomyScope("tenant", "client");
    final HostingRentFinishingMapper discovery = mock(HostingRentFinishingMapper.class);
    final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    final EconomyHostingRentMapper leases = mock(EconomyHostingRentMapper.class);
    final EconomyHostingRenewalMapper clock = mock(EconomyHostingRenewalMapper.class);
    final AgentTaskMutationTransaction roots = mock(AgentTaskMutationTransaction.class);
    final AgentTaskFormalDeliveryDao deliveries = mock(AgentTaskFormalDeliveryDao.class);
    final AgentTaskFormalDeliveryDecisionService decisions = mock(AgentTaskFormalDeliveryDecisionService.class);
    final AgentTaskCancellationService cancellations = mock(AgentTaskCancellationService.class);
    final HostingRentTaskSettlement service = new HostingRentTaskSettlement(discovery, bindings, leases, clock,
            roots, deliveries, decisions, cancellations, new FixedTenantAdmissionPolicy("tenant", "client"));
    final AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity().setId(7L).setJiacn("owner")
            .setPersonaCode("hero").setAgentId("agent").setStatus(1);
    final EconomyHostingLeaseEntity lease = new EconomyHostingLeaseEntity().setStatus("FINISHING")
            .setBindingId("7").setAgentId("agent").setLockedAt(1000L);
    final AgentTaskMetaEntity task = new AgentTaskMetaEntity().setId(1L).setTaskId("task")
            .setOwnerJiacn("owner").setAssignedAgentId("agent").setRewardStatus("running").setTaskVersion(4L);
    boolean rootLocked;
    @BeforeEach void setup() {
        when(discovery.findFinishingBinding("tenant", "client", "lease")).thenReturn(binding);
        when(discovery.unfinishedPage("tenant", "client", "owner", "agent", 0)).thenReturn(List.of(task));
        when(discovery.related("tenant", "client", "owner", "agent", "task")).thenReturn(1);
        when(roots.executeWithLockedTaskRootInOwnerScope(eq("tenant"), eq("client"), eq("owner"), eq("task"), any()))
                .thenAnswer(i -> { rootLocked=true; try {
                    return ((AgentTaskMutationTransaction.LockedTaskMutation<?>) i.getArgument(4)).apply(task);
                } finally { rootLocked=false; } });
        when(bindings.findExactActiveByScopeAndPersonaForUpdate("tenant", "client", "owner", "hero"))
                .thenAnswer(i -> { assertTrue(rootLocked); return binding; });
        when(leases.selectLeaseForUpdate("tenant", "client", "lease")).thenReturn(lease);
        when(clock.nowMillis()).thenReturn(1000L + 15L * 86400000);
    }
    @Test void expiredUnfinishedTaskUsesExistingCancellationAndRootBindingLeaseOrder() {
        service.settleExpiredTasks(scope, "lease");
        var order = inOrder(bindings, leases, cancellations);
        order.verify(bindings).findExactActiveByScopeAndPersonaForUpdate("tenant", "client", "owner", "hero");
        order.verify(leases).selectLeaseForUpdate("tenant", "client", "lease");
        order.verify(cancellations).cancel("tenant", "client", "owner", "hosting-rent-expiry", "task", 4L);
        verifyNoInteractions(decisions, deliveries);
    }
    @Test void deliveredTaskUsesSystemAcceptanceWithAuthoritativeVersions() {
        task.setRewardStatus("reviewing");
        when(deliveries.findLatestTaskForUpdate("tenant", "client", "task")).thenReturn(
                new AgentTaskFormalDeliveryEntity().setDeliveryId("delivery").setVersion(9L).setState("submitted"));
        service.settleExpiredTasks(scope, "lease");
        verify(decisions).acceptForHostingExpiry(eq("tenant"), eq("client"), eq("task"), eq("owner"), argThat(c ->
                "delivery".equals(c.getDeliveryId()) && c.getExpectedTaskVersion()==4 && c.getExpectedDeliveryVersion()==9
                        && "accepted".equals(c.getDecision()) && c.getReviewReason()==null));
        verifyNoInteractions(cancellations);
    }
    @Test void failedTaskReleasesThroughCancellationRatherThanBeingSkippedAsTerminal() {
        task.setRewardStatus("failed"); service.settleExpiredTasks(scope, "lease");
        verify(cancellations).cancel("tenant", "client", "owner", "hosting-rent-expiry", "task", 4L);
    }
    @Test void voluntaryRemovalAndGracePeriodDoNotForceOldTasks() {
        lease.setLockedAt(null); service.settleExpiredTasks(scope, "lease");
        lease.setLockedAt(1001L); service.settleExpiredTasks(scope, "lease");
        verifyNoInteractions(decisions, cancellations, deliveries);
    }
    @Test void renewedLeaseOrChangedBindingCannotSettleOldDiscovery() {
        lease.setStatus("ACTIVE"); service.settleExpiredTasks(scope, "lease");
        lease.setStatus("FINISHING").setBindingId("8"); service.settleExpiredTasks(scope, "lease");
        verifyNoInteractions(decisions, cancellations, deliveries);
    }
    @Test void relationshipChangeBeforeBindingLockDoesNotLockOrMutateNewLease() {
        when(bindings.findExactActiveByScopeAndPersonaForUpdate("tenant", "client", "owner", "hero"))
                .thenReturn(new AgentPersonaBindingEntity().setId(8L).setAgentId("agent"));
        service.settleExpiredTasks(scope, "lease"); verifyNoInteractions(leases, decisions, cancellations);
    }
    @Test void terminalOrReassignedTaskIsNotSettledAgain() {
        task.setRewardStatus("completed"); service.settleExpiredTasks(scope, "lease");
        task.setRewardStatus("running"); when(discovery.related("tenant", "client", "owner", "agent", "task")).thenReturn(0);
        service.settleExpiredTasks(scope, "lease"); verifyNoInteractions(decisions, cancellations, deliveries);
    }
    @Test void missingDeliveryDoesNotInventAcceptanceOrRefund() {
        task.setRewardStatus("reviewing");
        assertThrows(IllegalStateException.class, () -> service.settleExpiredTasks(scope, "lease"));
        verifyNoInteractions(decisions, cancellations);
    }
    @Test void staleScheduleAndForeignScopeNeverDiscoverOtherUsersTasks() {
        when(discovery.findFinishingBinding("tenant", "client", "lease")).thenReturn(null);
        service.settleExpiredTasks(scope, "lease");
        assertThrows(IllegalArgumentException.class, () -> service.settleExpiredTasks(new EconomyScope("foreign", "client"), "lease"));
        verify(discovery).findFinishingBinding("tenant", "client", "lease"); verifyNoMoreInteractions(discovery);
        verifyNoInteractions(bindings, leases, roots, decisions, cancellations);
    }
}
