package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.mapper.AgentWorkItemReassignmentMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkItemReassignmentMapperContractTest {
    @Test
    void receiptIdentityIsTaskRootSerializedExactScopedAndTargetIndependent() throws Exception {
        String receipt = sql(AgentWorkItemReassignmentMapper.class, "selectReceiptForUpdate");
        assertTrue(receipt.endsWith("for update"));
        for (String column : new String[] {
                "tenant_id", "client_id", "task_id", "work_item_id", "reassignment_id"}) {
            assertBinary(receipt, column);
        }
        String latest = sql(AgentWorkItemReassignmentMapper.class,
                "selectLatestReceiptForUpdate");
        assertTrue(latest.contains("order by id desc limit 1 for update"));
        assertFalse(receipt.contains("target_agent_id=#{targetagentid}"));
    }

    @Test
    void sourceCommandReadValidatesImmutableBytesWithoutTransportStatusAuthorization() throws Exception {
        String source = sql(AgentWorkItemReassignmentMapper.class, "selectSourceCommand");
        assertBinary(source, "tenant_id");
        assertBinary(source, "client_id");
        assertBinary(source, "command_id");
        assertTrue(source.contains("command_payload_hash"));
        assertFalse(source.contains("status='failed'"));
        assertFalse(source.contains("status='dead'"));
        assertFalse(source.contains("attempt_count>"));
        assertFalse(source.contains("for update"));
    }

    @Test
    void expiredLeaseCasFencesEveryOldLeaseFieldAndConsumesExactlyOneAttempt() throws Exception {
        String sql = sql(AgentTaskWorkItemMapper.class, "reassignExpiredLeaseByVersion");
        for (String token : new String[] {
                "assignee_agent_id=#{previousagentid}",
                "lease_token=#{previousleasetoken}", "status=#{expectedstatus}",
                "lease_until=#{expectedleaseuntil}", "lease_until<=#{expiredatorbefore}",
                "attempt_count+1=#{item.attemptcount}", "attempt_count+1<max_attempts",
                "version=#{expectedversion}", "status='claimed'", "version=version+1"}) {
            assertTrue(sql.contains(token), token + ": " + sql);
        }
        assertFalse(sql.contains("status='ready'"));
        assertFalse(sql.contains("command_payload="));
    }

    @Test
    void ddlStoresOnlyTokenDigestAndPermanentTargetIndependentReceiptIdentity() throws Exception {
        String ddl = Files.readString(Path.of(
                "src/main/resources/db/agent-work-item-reassignment-e05.sql"))
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertTrue(ddl.contains("unique key uk_work_item_reassignment_id (tenant_id, client_id, reassignment_id)"));
        assertTrue(ddl.contains("lease_fence_sha256"));
        assertFalse(ddl.contains(" lease_token "));
        assertTrue(ddl.contains("previous_agent_id <> target_agent_id"));
        assertTrue(ddl.contains("attempt_count < max_attempts"));
    }

    private static String sql(Class<?> type, String methodName) throws Exception {
        Method method = null;
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) method = candidate;
        }
        if (method == null) throw new NoSuchMethodException(methodName);
        Select select = method.getAnnotation(Select.class);
        Update update = method.getAnnotation(Update.class);
        Insert insert = method.getAnnotation(Insert.class);
        String[] value = select != null ? select.value()
                : update != null ? update.value() : insert.value();
        return String.join(" ", value).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }

    private static void assertBinary(String sql, String column) {
        assertTrue(sql.contains("cast(" + column + " as binary)"), column + ": " + sql);
        assertTrue(sql.contains("octet_length(" + column + ")"), column + ": " + sql);
    }
}
