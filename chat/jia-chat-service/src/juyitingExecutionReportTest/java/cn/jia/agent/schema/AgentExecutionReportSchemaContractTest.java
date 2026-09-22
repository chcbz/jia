package cn.jia.agent.schema;

import cn.jia.agent.mapper.AgentExecutionReportMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionReportSchemaContractTest {
    @Test
    void migrationIsAdditiveScopedAndHasBothReplayUniquenessBoundaries() throws Exception {
        String sql = new ClassPathResource("db/agent-execution-report-v1.sql")
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(sql.contains("create table if not exists agent_execution_report_head"));
        assertTrue(sql.contains("create table if not exists agent_execution_report_inbox"));
        assertTrue(sql.contains("runtime_instance_id varchar(100) not null"));
        assertTrue(sql.contains("unique key uk_execution_report_id (tenant_id,client_id,owner_jiacn,agent_id,report_id)"));
        assertTrue(sql.contains("unique key uk_execution_report_sequence (tenant_id,client_id,owner_jiacn,agent_id,command_id,attempt,sequence)"));
        assertTrue(sql.contains("message_type in ('work.progress','work.heartbeat','work.result','help.request','artifact.publish')"));
        assertFalse(sql.contains("foreign key"));
        assertFalse(sql.contains("insert into"));
        assertFalse(sql.contains("update agent_"));
        assertFalse(sql.contains("delete from"));
    }

    @Test
    void mapperLocksAuthoritativeExecutionBeforeReceiptRows() {
        String source = AgentExecutionReportMapper.class.toString();
        assertTrue(source.contains("AgentExecutionReportMapper"));
        assertTrue(java.util.Arrays.stream(AgentExecutionReportMapper.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("lockTransportDelivery")));
        assertTrue(java.util.Arrays.stream(AgentExecutionReportMapper.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("lockExecutionByCommand")));
        assertTrue(java.util.Arrays.stream(AgentExecutionReportMapper.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("lockHead")));
        assertTrue(java.util.Arrays.stream(AgentExecutionReportMapper.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("lockReport")));
    }
}
