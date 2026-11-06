package cn.jia.agent.service.usage;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeExecutionUsageCostRecorderTest {
    private final PersonalWorkspaceExecutionDao executions = mock(PersonalWorkspaceExecutionDao.class);
    private final UsageCostService costs = mock(UsageCostService.class);
    private final NativeExecutionUsageCostRecorder recorder = new NativeExecutionUsageCostRecorder(executions, costs);
    private final PersonalWorkspaceExecutionService.RuntimeScope scope =
            new PersonalWorkspaceExecutionService.RuntimeScope("0", "client", "executor", "agent", "instance");
    private final NativeExecutionUsageCostRecorder.Evidence evidence =
            new NativeExecutionUsageCostRecorder.Evidence("exec", NativeExecutionUsageCostRecorder.Phase.TERMINAL, "requested-only", "{\"status\":\"UNVERIFIED\",\"tokenUsage\":{\"last\":{\"inputTokens\":12},\"total\":{\"inputTokens\":90}}}");

    @Test void cancelledExecutionUsesHistoricalOwnerAndNeverGuessesUsagePriceOrReopensBusiness() {
        when(executions.findForNativeUsageCost("0", "client", "executor", "agent", "task", "run", "exec"))
                .thenReturn(new PersonalWorkspaceExecutionEntity().setExecutionId("exec").setTaskId("task")
                        .setRunId("run").setOwnerJiacn("task-owner").setExecutionState("CANCELLED"));
        recorder.record(scope, "task", "run", evidence);
        var saved = ArgumentCaptor.forClass(UsageCostService.Observation.class);
        verify(costs).record(saved.capture());
        assertEquals("task-owner", saved.getValue().binding().ownerJiacn());
        assertEquals("exec", saved.getValue().binding().sourceAttemptId());
        assertEquals("NATIVE_EXECUTION_EVIDENCE", saved.getValue().binding().source());
        assertEquals("terminal", saved.getValue().receiptId());
        assertEquals(1, saved.getValue().expectedRevision());
        assertNull(saved.getValue().normalizedUsage()); assertNull(saved.getValue().price());
        assertTrue(saved.getValue().rawUsageJson().contains("requested-only"));
        assertTrue(saved.getValue().rawUsageJson().contains("\"total\""));
        verify(executions).findForNativeUsageCost("0", "client", "executor", "agent", "task", "run", "exec");
        verifyNoMoreInteractions(executions, costs);
    }

    @Test void startedObservationCreatesSamePendingAttemptBeforeAnyKnownUsage() {
        when(executions.findForNativeUsageCost("0", "client", "executor", "agent", "task", "run", "exec"))
                .thenReturn(new PersonalWorkspaceExecutionEntity().setExecutionId("exec").setTaskId("task")
                        .setRunId("run").setOwnerJiacn("task-owner"));
        recorder.record(scope, "task", "run", new NativeExecutionUsageCostRecorder.Evidence(
                "exec", NativeExecutionUsageCostRecorder.Phase.STARTED, "requested-only", "null"));
        var saved = ArgumentCaptor.forClass(UsageCostService.Observation.class);
        verify(costs).record(saved.capture());
        assertEquals("started", saved.getValue().receiptId());
        assertEquals(0, saved.getValue().expectedRevision());
        assertEquals("exec", saved.getValue().binding().sourceAttemptId());
        assertNull(saved.getValue().normalizedUsage()); assertNull(saved.getValue().price());
    }

    @Test void missingHistoricalAuthorityNeverWritesCosts() {
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> recorder.record(scope, "task", "run", evidence));
        verifyNoInteractions(costs);
    }
}
