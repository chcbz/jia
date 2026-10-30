package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.mapper.AgentExecutionReportMapper;
import cn.jia.agent.service.AgentExecutionReportService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentExecutionReportTenantTest {
    @Test
    void grayReportCannotResolveOrWriteProductionExecution() {
        var executions = mock(PersonalWorkspaceExecutionDao.class);
        var reports = mock(AgentExecutionReportMapper.class);
        var service = new AgentExecutionReportServiceImpl(executions, reports);
        var command = new AgentExecutionReportService.ReportCommand("work.progress", "msg-1", "report-1",
                "command-1", "dispatch-1", "execution-1", 1L, 1, "1", 1L, 1L,
                Map.of("status", "working"));
        var missing = assertThrows(AgentExecutionReportService.Failure.class, () -> service.accept(
                new AgentExecutionReportService.RuntimeScope("1", "client", "owner", "agent", "runtime"), command));
        assertEquals(AgentExecutionReportService.Reason.NOT_FOUND, missing.reason());
        verify(executions).lock("1", "client", "owner", "execution-1");
        verify(executions, never()).lock("0", "client", "owner", "execution-1");
        verifyNoInteractions(reports);
        var invalid = assertThrows(AgentExecutionReportService.Failure.class, () -> service.accept(
                new AgentExecutionReportService.RuntimeScope("1 ", "client", "owner", "agent", "runtime"), command));
        assertEquals(AgentExecutionReportService.Reason.INVALID_REQUEST, invalid.reason());
        verifyNoMoreInteractions(executions);
    }
}
