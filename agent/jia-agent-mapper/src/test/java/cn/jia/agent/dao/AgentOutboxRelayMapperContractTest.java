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
        for (String methodName : new String[] {
                "selectCorruptCandidates", "selectDueCandidates", "selectStaleCandidates"}) {
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
        assertTrue(due.contains("o.status='pending'"));
        assertTrue(due.contains("o.next_retry_at is null or o.next_retry_at<=#{now}"));
        assertTrue(due.contains("o.status='retry'"));
        assertTrue(due.contains("o.next_retry_at is not null and o.next_retry_at<=#{now}"));
        String stale = sql(method("selectStaleCandidates"));
        assertTrue(stale.contains("o.status='claimed'"));
        assertTrue(stale.contains("o.lease_until is not null and o.lease_until<=#{now}"));
    }


    @Test
    void normalDiscoveryExcludesDeterminablePoisonAndCorruptionLaneSelectsIt() throws Exception {
        String corrupt = sql(method("selectCorruptCandidates"));
        assertTrue(corrupt.contains("and not ("));
        assertTrue(corrupt.contains("o.version<9223372036854775806"));
        assertTrue(corrupt.contains("d.version>=9223372036854775806"));
        for (String methodName : new String[] {"selectDueCandidates", "selectStaleCandidates"}) {
            String sql = sql(method(methodName));
            assertTrue(sql.contains("o.delivery_id>0"), methodName);
            assertTrue(sql.contains("char_length(o.tenant_id) between 1 and 50"), methodName);
            assertTrue(sql.contains("char_length(o.client_id) between 1 and 50"), methodName);
            assertTrue(sql.contains(
                    "not regexp concat('[',char(92),'p{cc}]')"), methodName);
            assertTrue(sql.contains("exists ( select 1 from agent_command_delivery"), methodName);
            assertTrue(sql.contains("o.version<9223372036854775806"), methodName);
            assertTrue(sql.contains("d.version>=9223372036854775806"), methodName);
        }
    }

    @Test
    void globalQuarantineLockAndMutationsUseExactRawFencesAndSaturatingVersion() throws Exception {
        String lock = sql(method("selectOutboxForQuarantine"));
        for (String value : new String[] {"id=#{outboxid}",
                "delivery_id=#{deliveryid}", "version=#{outboxversion}",
                "status=#{outboxstatus}", "cast(tenant_id as binary)",
                "octet_length(tenant_id)", "cast(client_id as binary)",
                "octet_length(client_id)"}) {
            assertTrue(lock.contains(value), value);
        }
        assertTrue(lock.endsWith("for update"), lock);
        for (String methodName : new String[] {"quarantineDelivery", "quarantineOutbox"}) {
            String sql = sql(method(methodName));
            assertTrue(sql.contains("status='dead'"), methodName);
            assertTrue(sql.contains(
                    "version=version+case when version<9223372036854775807 then 1 else 0 end"),
                    methodName);
            assertTrue(sql.contains("and version=#{"), methodName);
            assertTrue(sql.contains("and status=#{"), methodName);
            assertTrue(sql.contains("cast(tenant_id as binary)"), methodName);
            assertTrue(sql.contains("cast(client_id as binary)"), methodName);
        }
    }

    @Test
    void rowLocksAreSeparateDeliveryThenOutboxPrimitivesWithExactScope() throws Exception {
        assertLock("selectDeliveryForUpdate");
        assertLock("selectOutboxForUpdate");
        assertLock("selectPreviousAttemptOutboxesForUpdate");
        assertTrue(sql(method("selectPreviousAttemptOutboxesForUpdate"))
                .contains("active_attempt=#{previousattempt}"));
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
        assertFalse(outbox.contains("active_attempt=active_attempt+1"));
        assertTrue(delivery.contains("version<9223372036854775806"));
        assertTrue(outbox.contains("version<9223372036854775806"));
    }

    @Test
    void settleMutationsFenceCurrentRowsAndNeverIncrementTransportAttempt() throws Exception {
        String delivery = sql(method("disposeDelivery"));
        assertTrue(delivery.contains("version=#{delivery.version}"));
        assertTrue(delivery.contains("active_attempt=#{delivery.activeattempt}"));
        assertFalse(delivery.contains("attempt_count=attempt_count+1"));
        assertTrue(delivery.contains("version<9223372036854775807"));
        String outbox = sql(method("disposeOutbox"));
        assertTrue(outbox.contains("version=#{outbox.version}"));
        assertTrue(outbox.contains("active_attempt=#{outbox.activeattempt}"));
        assertTrue(outbox.contains("attempt_count=#{outbox.attemptcount}"));
        assertTrue(outbox.contains("version<9223372036854775807"));
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
