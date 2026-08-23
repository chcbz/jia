package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentCommandInboxMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandInboxMapperContractTest {
    @Test
    void d02TransportDaoRemainsUnextendedForExistingTestDoubles() {
        assertEquals(3, AgentCommandTransportDao.class.getDeclaredMethods().length);
    }

    @Test
    void sourceAndInboxLocksAreForUpdateWithBinaryOctetScopePredicates() throws Exception {
        assertLock("selectDeliveryForUpdate", "tenant_id", "client_id");
        assertLock("selectOutboxForUpdate", "tenant_id", "client_id", "event_id");
        assertLock("selectInboxForUpdate",
                "tenant_id", "client_id", "consumer_name", "message_id");
    }

    @Test
    void allCasMutationsFenceVersionStatusIdentityAndActiveAttempt() throws Exception {
        for (String methodName : new String[] {
                "updateDeliveryDisposition", "reclaimProcessingInbox",
                "reclaimRetryInbox", "expireRetryInbox", "completeInbox"}) {
            Method method = method(methodName);
            String sql = sql(method);
            assertTrue(sql.contains("version="), methodName);
            assertTrue(sql.contains("status="), methodName);
            assertTrue(sql.contains("active_attempt="), methodName);
            assertBinary(sql, "tenant_id", methodName);
            assertBinary(sql, "client_id", methodName);
        }
        String delivery = sql(method("updateDeliveryDisposition"));
        assertBinary(delivery, "active_message_id", "delivery");
        String complete = sql(method("completeInbox"));
        assertBinary(complete, "consumer_name", "complete");
        assertBinary(complete, "message_id", "complete");
        assertBinary(complete, "lease_owner", "complete");
    }

    @Test
    void inboxInsertUsesD01ColumnsWithoutSchemaMutationOrPayloadTextConversion() throws Exception {
        Method method = method("insertInbox");
        String sql = normalize(String.join(" ", method.getAnnotation(Insert.class).value()));
        for (String column : new String[] {
                "wire_payload", "wire_payload_hash", "event_id", "command_id", "delivery_id",
                "attempt_count", "active_attempt", "lease_owner", "lease_until", "version"}) {
            assertTrue(sql.contains(column), column);
        }
        assertFalse(sql.contains("json"));
        assertFalse(sql.contains("text"));
    }

    private static void assertLock(String methodName, String... exactColumns) throws Exception {
        String sql = normalize(String.join(" ", method(methodName).getAnnotation(Select.class).value()));
        assertTrue(sql.endsWith("for update"), methodName + ": " + sql);
        for (String column : exactColumns) assertBinary(sql, column, methodName);
    }

    private static void assertBinary(String sql, String column, String label) {
        assertTrue(sql.contains("cast(" + column + " as binary)"), label + ": " + sql);
        assertTrue(sql.contains("octet_length(" + column + ")"), label + ": " + sql);
    }

    private static Method method(String name) throws Exception {
        for (Method method : AgentCommandInboxMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) return method;
        }
        throw new NoSuchMethodException(name);
    }

    private static String sql(Method method) {
        Update update = method.getAnnotation(Update.class);
        if (update != null) return normalize(String.join(" ", update.value()));
        Select select = method.getAnnotation(Select.class);
        if (select != null) return normalize(String.join(" ", select.value()));
        throw new IllegalArgumentException(method.getName());
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
