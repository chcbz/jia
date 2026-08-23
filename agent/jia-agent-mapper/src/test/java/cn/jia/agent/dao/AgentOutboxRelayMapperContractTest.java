package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentOutboxRelayMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOutboxRelayMapperContractTest {
    @Test
    void discoveryIsUnlockedBoundedAndDeterministicallyOrderedInBothLanes() throws Exception {
        for (String methodName : new String[] {"selectDueCandidates", "selectStaleCandidates"}) {
            String sql = sql(method(methodName));
            assertTrue(sql.contains("order by"), methodName);
            assertTrue(sql.contains("eligibleat asc") || sql.contains("lease_until asc"), methodName);
            assertTrue(sql.contains("id asc"), methodName);
            assertTrue(sql.contains("limit #{limit}"), methodName);
            assertFalse(sql.contains("for update"), methodName);
            assertFalse(sql.contains("skip locked"), methodName);
        }
    }

    @Test
    void dueAndStaleLanesHaveExactStatusAndTimePredicates() throws Exception {
        String due = sql(method("selectDueCandidates"));
        assertTrue(due.contains("status='pending'"));
        assertTrue(due.contains("next_retry_at is null or next_retry_at<=#{now}"));
        assertTrue(due.contains("status='retry'"));
        assertTrue(due.contains("next_retry_at is not null and next_retry_at<=#{now}"));
        String stale = sql(method("selectStaleCandidates"));
        assertTrue(stale.contains("status='claimed'"));
        assertTrue(stale.contains("lease_until is not null and lease_until<=#{now}"));
    }

    @Test
    void rowLocksAreSeparateDeliveryThenOutboxPrimitivesWithExactScope() throws Exception {
        assertLock("selectDeliveryForUpdate");
        assertLock("selectOutboxForUpdate");
    }

    @Test
    void claimMutationsFenceIdentityStatusAttemptsAndVersion() throws Exception {
        String delivery = sql(method("claimDelivery"));
        for (String value : new String[] {"version=#{delivery.version}",
                "active_message_id=#{delivery.activemessageid}",
                "active_attempt=#{delivery.activeattempt}", "status=#{delivery.status}"}) {
            assertTrue(delivery.contains(value), value);
        }
        String outbox = sql(method("claimOutbox"));
        for (String value : new String[] {"version=#{outbox.version}",
                "active_attempt=#{outbox.activeattempt}",
                "attempt_count=#{outbox.attemptcount}", "status=#{outbox.status}",
                "event_id=#{outbox.eventid}", "message_id=#{outbox.messageid}",
                "command_id=#{outbox.commandid}", "delivery_id=#{outbox.deliveryid}"}) {
            assertTrue(outbox.contains(value), value);
        }
        assertTrue(outbox.contains("attempt_count=attempt_count+1"));
        assertTrue(outbox.contains("active_attempt=active_attempt+1"));
    }

    @Test
    void settleMutationsFenceCurrentRowsAndNeverIncrementTransportAttempt() throws Exception {
        String delivery = sql(method("disposeDelivery"));
        assertTrue(delivery.contains("version=#{delivery.version}"));
        assertTrue(delivery.contains("active_attempt=#{delivery.activeattempt}"));
        assertFalse(delivery.contains("attempt_count=attempt_count+1"));
        String outbox = sql(method("disposeOutbox"));
        assertTrue(outbox.contains("version=#{outbox.version}"));
        assertTrue(outbox.contains("active_attempt=#{outbox.activeattempt}"));
        assertTrue(outbox.contains("attempt_count=#{outbox.attemptcount}"));
    }

    private static void assertLock(String methodName) throws Exception {
        String sql = sql(method(methodName));
        assertTrue(sql.endsWith("for update"), sql);
        assertTrue(sql.contains("cast(tenant_id as binary)"), sql);
        assertTrue(sql.contains("octet_length(tenant_id)"), sql);
        assertTrue(sql.contains("cast(client_id as binary)"), sql);
        assertTrue(sql.contains("octet_length(client_id)"), sql);
        assertFalse(sql.contains("skip locked"), sql);
    }

    private static Method method(String name) throws Exception {
        for (Method method : AgentOutboxRelayMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) return method;
        }
        throw new NoSuchMethodException(name);
    }

    private static String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null) return normalize(String.join(" ", select.value()));
        Update update = method.getAnnotation(Update.class);
        if (update != null) return normalize(String.join(" ", update.value()));
        throw new IllegalArgumentException(method.getName());
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
