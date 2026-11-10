package cn.jia.agent.hosting;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.skill.SkillManagementService;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.hosting.HostingRentRenewalService;
import cn.jia.economy.service.EconomyScope;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduledInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Lifecycle ordering/unknown handling. Runtime UDS, SQL settlement and private cleanup have separate real regressions. */
class HostingRentFinishingHandlerTest {
    final HostingRentFinishingMapper discovery=mock(HostingRentFinishingMapper.class);
    final HostingRentRenewalService renewal=mock(HostingRentRenewalService.class);
    final HostingRentTaskSettlement tasks=mock(HostingRentTaskSettlement.class);
    final HostingRentManagedStop stop=mock(HostingRentManagedStop.class);
    final ManagedHostingProvisioner runtime=mock(ManagedHostingProvisioner.class);
    final SkillManagementService skills=mock(SkillManagementService.class);
    final HostingRentHandoverCompletion completion=mock(HostingRentHandoverCompletion.class);
    final HostingRentFinishingHandler handler=new HostingRentFinishingHandler(new FixedTenantAdmissionPolicy("tenant","client"),
            discovery,renewal,tasks,stop,runtime,skills,completion);
    final EconomyScope scope=new EconomyScope("tenant","client");
    final ScheduledInvocation invocation=new ScheduledInvocation("tenant",new ScheduleKey("client",HostingRentLeaseWakeup.FINISH,"lease",""),1);
    final ManagedHostingProvisioner.Preparation initial=new ManagedHostingProvisioner.Preparation("tenant","client","owner","agent","intent","lease","7",1000);
    final ManagedHostingProvisioner.Candidate candidate=new ManagedHostingProvisioner.Candidate("installation","manifest","secret",2000,2,"host");
    final ManagedHostingProvisioner.CurrentOperation current=new ManagedHostingProvisioner.CurrentOperation(initial,candidate);
    void ready() {
        var binding=new AgentPersonaBindingEntity();binding.setId(7L);binding.setJiacn("owner");binding.setAgentId("agent");
        when(discovery.findFinishingBinding("tenant","client","lease")).thenReturn(binding);
        when(renewal.initialHandoverIntent(scope,"lease")).thenReturn(new EconomyHostingProvisioningIntentEntity().setIntentId("intent").setReservedAt(1000L));
        when(stop.stop(initial)).thenReturn(current);
        when(runtime.flushEvidence(initial,candidate,"lease")).thenReturn(true);
        when(skills.settleHandoverChanges(current)).thenReturn(true);
        when(runtime.cleanup(initial,candidate,"lease")).thenReturn(true);
    }
    @Test void finalReleaseFollowsTaskStopEvidenceFinancialAndPrivateCleanupInOrder() {
        ready();handler.executeScheduled(invocation);assertEquals(HostingRentLeaseWakeup.FINISH,handler.getName());
        var order=inOrder(tasks,stop,runtime,skills,completion);
        order.verify(tasks).settleExpiredTasks(scope,"lease");order.verify(stop).stop(initial);
        order.verify(runtime).flushEvidence(initial,candidate,"lease");order.verify(skills).settleHandoverChanges(current);
        order.verify(runtime).cleanup(initial,candidate,"lease");order.verify(completion).completeManaged(current);
        verify(runtime,never()).prepareAndObserve(any());
    }
    @Test void unknownStopRetainsScheduleAndNeverFlushesOrReleases() {
        ready();when(stop.stop(initial)).thenReturn(null);
        assertThrows(IllegalStateException.class,()->handler.executeScheduled(invocation));verifyNoInteractions(runtime,skills,completion);
    }
    @Test void evidenceFailureCannotSettleOrClean() {
        ready();when(runtime.flushEvidence(initial,candidate,"lease")).thenReturn(false);
        assertThrows(IllegalStateException.class,()->handler.executeScheduled(invocation));verifyNoInteractions(skills,completion);
        verify(runtime,never()).cleanup(any(),any(),any());
    }
    @Test void heldUnknownFeesPreventCleanupAndRelease() {
        ready();when(skills.settleHandoverChanges(current)).thenReturn(false);
        assertThrows(IllegalStateException.class,()->handler.executeScheduled(invocation));verifyNoInteractions(completion);
        verify(runtime,never()).cleanup(any(),any(),any());
    }
    @Test void unconfirmedPrivateCleanupDoesNotReleaseRelationship() {
        ready();when(runtime.cleanup(initial,candidate,"lease")).thenReturn(false);
        assertThrows(IllegalStateException.class,()->handler.executeScheduled(invocation));verifyNoInteractions(completion);
    }
    @Test void finalDatabaseFailureReconcilesSameOriginalHandoverWithoutNewProvision() {
        ready();doThrow(new IllegalStateException("commit unknown")).doNothing().when(completion).completeManaged(current);
        assertThrows(IllegalStateException.class,()->handler.executeScheduled(invocation));handler.executeScheduled(invocation);
        verify(stop,times(2)).stop(initial);verify(runtime,times(2)).cleanup(initial,candidate,"lease");
        verify(runtime,never()).prepareAndObserve(any());
    }
    @Test void staleClosedKeyDoesNotResolveOrTouchNewInvitation() {
        handler.executeScheduled(invocation);verifyNoInteractions(renewal,tasks,stop,runtime,skills,completion);
    }
    @Test void wrongPartitionOrAmbientTransactionRejectedBeforeDiscovery() {
        assertThrows(IllegalArgumentException.class,()->handler.executeScheduled(new ScheduledInvocation("other",invocation.key(),1)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(IllegalStateException.class,()->handler.executeScheduled(invocation));}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(discovery,renewal,tasks,stop,runtime,skills,completion);
    }
    @Test void missingOriginalIntentNeverInventsNewPreparation() {
        ready();when(renewal.initialHandoverIntent(scope,"lease")).thenReturn(null);
        assertThrows(IllegalStateException.class,()->handler.executeScheduled(invocation));verifyNoInteractions(tasks,stop,runtime,skills,completion);
    }
}
