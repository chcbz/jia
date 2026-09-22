package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentExecutionReportEntity;
import cn.jia.agent.entity.AgentExecutionReportHeadEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.mapper.AgentExecutionReportMapper;
import cn.jia.agent.service.AgentExecutionReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutionReportServiceImplTest {
    private final PersonalWorkspaceExecutionDao executions = mock(PersonalWorkspaceExecutionDao.class);
    private final AgentExecutionReportMapper reports = mock(AgentExecutionReportMapper.class);
    private final AtomicReference<AgentExecutionReportHeadEntity> head = new AtomicReference<>();
    private final AtomicReference<AgentExecutionReportEntity> receipt = new AtomicReference<>();
    private AgentExecutionReportServiceImpl service;
    private PersonalWorkspaceExecutionEntity execution;
    private AgentExecutionReportService.RuntimeScope scope;

    @BeforeEach
    void setUp() {
        service = new AgentExecutionReportServiceImpl(executions, reports);
        scope = new AgentExecutionReportService.RuntimeScope(
                "0", "client-1", "owner-1", "agent-1", "runtime-1");
        execution = new PersonalWorkspaceExecutionEntity()
                .setExecutionId("execution-1").setOwnerJiacn("owner-1")
                .setTargetAgentId("agent-1").setExecutionState("QUEUED").setGrantRevision(1L);
        execution.setTenantId("0");
        execution.setClientId("client-1");
        when(reports.lockExecutionByCommand(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(execution);
        when(executions.lock("0", "client-1", "owner-1", "execution-1")).thenReturn(execution);
        when(reports.lockHead(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> head.get());
        when(reports.insertHead(any())).thenAnswer(invocation -> {
            AgentExecutionReportHeadEntity inserted = invocation.getArgument(0);
            inserted.setId(1L);
            head.set(inserted);
            return 1;
        });
        when(reports.lockReport(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    AgentExecutionReportEntity current = receipt.get();
                    return current != null && current.getReportId().equals(invocation.getArgument(4))
                            ? current : null;
                });
        when(reports.insertReport(any())).thenAnswer(invocation -> {
            AgentExecutionReportEntity inserted = invocation.getArgument(0);
            inserted.setId(1L);
            receipt.set(inserted);
            return 1;
        });
        when(reports.advanceHead(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyLong(), anyLong(), any(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    AgentExecutionReportHeadEntity current = invocation.getArgument(0);
                    current.setLastSequence(invocation.getArgument(7));
                    current.setCommittedVersion(invocation.getArgument(8));
                    current.setTerminalReportId(invocation.getArgument(9));
                    current.setTerminalResultRef(invocation.getArgument(10));
                    return 1;
                });
    }

    @Test
    void codexResultCommitsOnceReplaysAndRejectsChangedReplay() {
        AgentExecutionReportService.ReportCommand command = codex("report-1", "SUCCEEDED", 0);
        AgentExecutionReportService.ReportReceipt first = service.accept(scope, command);
        AgentExecutionReportService.ReportReceipt duplicate = service.accept(scope, command);

        assertFalse(first.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(first.resultRef(), duplicate.resultRef());
        assertEquals("1", first.committedVersion());
        assertEquals("execution-1", receipt.get().getExecutionRef());
        assertEquals("runtime-1", receipt.get().getRuntimeInstanceId());
        assertEquals(1L, receipt.get().getGrantRevision());
        assertEquals(1L, receipt.get().getSequence());

        AgentExecutionReportService.Failure conflict = assertThrows(
                AgentExecutionReportService.Failure.class,
                () -> service.accept(scope, codex("report-1", "FAILED", 9)));
        assertEquals(AgentExecutionReportService.Reason.CONFLICT, conflict.reason());
    }

    @Test
    void d03ResultUsesExactTransportCommandAndActiveDispatchFence() {
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(9L).setCommandId("command-1").setOwnerJiacn("owner-1")
                .setTargetAgentId("agent-1").setCommandType("TASK_EXECUTE")
                .setCommandPayload("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setCommandPayloadHash(sha256("{}"))
                .setStatus("SENT").setAttemptCount(2).setActiveMessageId("dispatch-1")
                .setActiveAttempt(2).setExpiresAt(1L).setVersion(3L);
        delivery.setTenantId("0");
        delivery.setClientId("client-1");
        when(reports.lockTransportDelivery("0", "client-1", "owner-1", "command-1"))
                .thenReturn(delivery);

        AgentExecutionReportService.ReportCommand command = new AgentExecutionReportService.ReportCommand(
                "work.result", "report-transport", "report-transport", "command-1", "dispatch-1",
                null, 0, 0, null, 0, 0,
                Map.of("resultType", "CODEX_EXECUTION_RESULT", "status", "SUCCEEDED", "exitCode", 0));
        AgentExecutionReportService.ReportReceipt accepted = service.accept(scope, command);

        assertFalse(accepted.duplicate());
        assertEquals(AgentExecutionReportServiceImpl.transportExecutionRef(
                        scope, "command-1", "dispatch-1", 2),
                receipt.get().getExecutionRef());
        assertEquals(2L, receipt.get().getGrantRevision());
        assertEquals(2, receipt.get().getAttempt());
        assertEquals("dispatch-1", receipt.get().getFencingToken());
        verify(executions, never()).lock(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void d03ResultRejectsMismatchedCorrelationWithoutPersistingReceipt() {
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(9L).setCommandId("command-1").setOwnerJiacn("owner-1")
                .setTargetAgentId("agent-1").setCommandType("TASK_EXECUTE")
                .setCommandPayload("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setCommandPayloadHash(sha256("{}"))
                .setStatus("SENT").setAttemptCount(1).setActiveMessageId("dispatch-1")
                .setActiveAttempt(1).setExpiresAt(1L).setVersion(3L);
        delivery.setTenantId("0");
        delivery.setClientId("client-1");
        when(reports.lockTransportDelivery("0", "client-1", "owner-1", "command-1"))
                .thenReturn(delivery);
        AgentExecutionReportService.ReportCommand mismatched = new AgentExecutionReportService.ReportCommand(
                "work.result", "report-mismatch", "report-mismatch", "command-1", "dispatch-other",
                null, 0, 0, null, 0, 0,
                Map.of("resultType", "CODEX_EXECUTION_RESULT", "status", "FAILED", "exitCode", 9));

        AgentExecutionReportService.Failure hidden = assertThrows(
                AgentExecutionReportService.Failure.class, () -> service.accept(scope, mismatched));
        assertEquals(AgentExecutionReportService.Reason.NOT_FOUND, hidden.reason());
        verify(reports, never()).insertHead(any());
        verify(reports, never()).insertReport(any());
    }

    @Test
    void terminalResultRejectsLaterProgressWithoutDispatchOrTaskMutation() {
        service.accept(scope, codex("report-1", "SUCCEEDED", 0));
        AgentExecutionReportService.ReportCommand progress = new AgentExecutionReportService.ReportCommand(
                "work.progress", "report-2", "report-2",
                AgentExecutionReportServiceImpl.commandId("execution-1"),
                AgentExecutionReportServiceImpl.dispatchMessageId("execution-1"),
                "execution-1", 1, 1, "1", 2, 10, Map.of("status", "RUNNING"));

        AgentExecutionReportService.Failure conflict = assertThrows(
                AgentExecutionReportService.Failure.class, () -> service.accept(scope, progress));
        assertEquals(AgentExecutionReportService.Reason.CONFLICT, conflict.reason());
        verify(executions, never()).update(any());
    }

    @Test
    void commandAttemptIsPinnedToFirstRuntimeInstance() {
        AgentExecutionReportService.ReportCommand progress = new AgentExecutionReportService.ReportCommand(
                "work.progress", "report-runtime-1", "report-runtime-1",
                AgentExecutionReportServiceImpl.commandId("execution-1"),
                AgentExecutionReportServiceImpl.dispatchMessageId("execution-1"),
                "execution-1", 1, 1, "1", 1, 10, Map.of("status", "RUNNING"));
        service.accept(scope, progress);

        AgentExecutionReportService.RuntimeScope otherRuntime =
                new AgentExecutionReportService.RuntimeScope(
                        "0", "client-1", "owner-1", "agent-1", "runtime-2");
        AgentExecutionReportService.ReportCommand second = new AgentExecutionReportService.ReportCommand(
                "work.progress", "report-runtime-2", "report-runtime-2",
                AgentExecutionReportServiceImpl.commandId("execution-1"),
                AgentExecutionReportServiceImpl.dispatchMessageId("execution-1"),
                "execution-1", 1, 1, "1", 2, 11, Map.of("status", "RUNNING"));

        AgentExecutionReportService.Failure hidden = assertThrows(
                AgentExecutionReportService.Failure.class,
                () -> service.accept(otherRuntime, second));
        assertEquals(AgentExecutionReportService.Reason.NOT_FOUND, hidden.reason());
        assertEquals("report-runtime-1", receipt.get().getReportId());
    }

    @Test
    void invisibleAgentAndStaleGrantFailAsNotFound() {
        execution.setTargetAgentId("agent-other");
        AgentExecutionReportService.Failure hidden = assertThrows(
                AgentExecutionReportService.Failure.class,
                () -> service.accept(scope, codex("report-hidden", "SUCCEEDED", 0)));
        assertEquals(AgentExecutionReportService.Reason.NOT_FOUND, hidden.reason());

        execution.setTargetAgentId("agent-1");
        AgentExecutionReportService.ReportCommand stale = new AgentExecutionReportService.ReportCommand(
                "work.progress", "report-stale", "report-stale",
                AgentExecutionReportServiceImpl.commandId("execution-1"),
                AgentExecutionReportServiceImpl.dispatchMessageId("execution-1"),
                "execution-1", 2, 1, "2", 1, 10, Map.of("status", "RUNNING"));
        AgentExecutionReportService.Failure staleFailure = assertThrows(
                AgentExecutionReportService.Failure.class, () -> service.accept(scope, stale));
        assertEquals(AgentExecutionReportService.Reason.NOT_FOUND, staleFailure.reason());
    }

    @Test
    void payloadAllowlistRejectsCredentialsUrlsPathsAndUnknownFields() {
        for (String unsafe : new String[] {"credential", "url", "path", "unknown"}) {
            AgentExecutionReportService.ReportCommand invalid = new AgentExecutionReportService.ReportCommand(
                    "work.result", "report-" + unsafe, "report-" + unsafe,
                    AgentExecutionReportServiceImpl.commandId("execution-1"),
                    AgentExecutionReportServiceImpl.dispatchMessageId("execution-1"),
                    null, 0, 0, null, 0, 0,
                    Map.of("resultType", "CODEX_EXECUTION_RESULT", "status", "SUCCEEDED",
                            "exitCode", 0, unsafe, "secret"));
            AgentExecutionReportService.Failure failure = assertThrows(
                    AgentExecutionReportService.Failure.class, () -> service.accept(scope, invalid));
            assertEquals(AgentExecutionReportService.Reason.INVALID_REQUEST, failure.reason());
        }
    }

    private AgentExecutionReportService.ReportCommand codex(String reportId, String status, int exitCode) {
        return new AgentExecutionReportService.ReportCommand(
                "work.result", reportId, reportId,
                AgentExecutionReportServiceImpl.commandId("execution-1"),
                AgentExecutionReportServiceImpl.dispatchMessageId("execution-1"),
                null, 0, 0, null, 0, 0,
                Map.of("resultType", "CODEX_EXECUTION_RESULT", "status", status,
                        "exitCode", exitCode));
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
