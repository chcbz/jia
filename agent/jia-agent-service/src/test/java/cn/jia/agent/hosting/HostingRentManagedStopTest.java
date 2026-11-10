package cn.jia.agent.hosting;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Coordinator sequencing only; real UDS and DB transaction evidence are separate suites. */
class HostingRentManagedStopTest {
    final ManagedHostingProvisioner runtime=mock(ManagedHostingProvisioner.class);
    final HostingRentHandoverCompletion relationships=mock(HostingRentHandoverCompletion.class);
    final HostingRentManagedStop service=new HostingRentManagedStop(runtime,relationships);
    final ManagedHostingProvisioner.Preparation initial=new ManagedHostingProvisioner.Preparation(
            "tenant","client","owner","agent","intent","lease","7",1000);
    final ManagedHostingProvisioner.Preparation active=new ManagedHostingProvisioner.Preparation(
            "tenant","client","owner","agent","intent","lease","7",1000,"reprovision",2000,3000L);
    final ManagedHostingProvisioner.Candidate candidate=new ManagedHostingProvisioner.Candidate("installation","manifest",null,0,2,"host");
    final ManagedHostingProvisioner.CurrentOperation current=new ManagedHostingProvisioner.CurrentOperation(active,candidate);
    void ready() {
        when(runtime.currentOperation(initial)).thenReturn(current);
        when(relationships.prepareManagedStop(current)).thenReturn(true);
        when(runtime.quiesce(active,candidate,"lease")).thenReturn(true);
    }
    @Test void recordsStopOnlyAfterRealCurrentGenerationConfirmationWithoutActivationOrCleanup() {
        ready();assertSame(current,service.stop(initial));
        var order=inOrder(runtime,relationships);
        order.verify(runtime).currentOperation(initial);order.verify(relationships).prepareManagedStop(current);
        order.verify(runtime).quiesce(active,candidate,"lease");order.verify(relationships).recordManagedStop(current);
        verifyNoMoreInteractions(runtime,relationships);
    }
    @Test void unknownCurrentOrUnconfirmedStopCannotPublishStopFact() {
        assertNull(service.stop(initial));verifyNoInteractions(relationships);
        ready();when(runtime.quiesce(active,candidate,"lease")).thenReturn(false);
        assertNull(service.stop(initial));verify(relationships,never()).recordManagedStop(any());
    }
    @Test void closedRelationshipOrUnfinishedTasksNeverStopsAnExecutor() {
        ready();when(relationships.prepareManagedStop(current)).thenReturn(false);
        assertNull(service.stop(initial));verify(runtime,never()).quiesce(any(),any(),anyString());
        when(relationships.prepareManagedStop(current)).thenThrow(new IllegalStateException("old tasks remain"));
        assertThrows(IllegalStateException.class,()->service.stop(initial));
        verify(runtime,never()).quiesce(any(),any(),anyString());verify(relationships,never()).recordManagedStop(any());
    }
    @Test void lostCommitRetriesSameHandoverWithoutPrepareEnrollOrNewGeneration() {
        ready();doThrow(new IllegalStateException("commit unknown")).doNothing().when(relationships).recordManagedStop(current);
        assertThrows(IllegalStateException.class,()->service.stop(initial));
        assertSame(current,service.stop(initial));
        verify(runtime,times(2)).currentOperation(initial);verify(runtime,times(2)).quiesce(active,candidate,"lease");
        verify(runtime,never()).prepareAndObserve(any());verify(runtime,never()).cleanup(any(),any(),anyString());
    }
    @Test void refusesProcessControlUnderCallerDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(IllegalStateException.class,()->service.stop(initial));}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(runtime,relationships);
    }
}
