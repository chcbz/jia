package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentCommandTransportMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandMailboxMapperContractTest {
    @Test
    void mailboxProjectionIsExactScopedBoundedAndStable() throws Exception {
        String sql = sql("selectMailboxPage");

        assertTrue(sql.contains("where d.tenant_id=#{tenantid} and d.client_id=#{clientid}"));
        assertExactParameter(sql, "d.tenant_id", "tenantid");
        assertExactParameter(sql, "d.client_id", "clientid");
        assertExactParameter(sql, "d.target_agent_id", "targetagentid");
        assertTrue(sql.contains("d.task_id=#{taskid}"));
        assertTrue(sql.contains("d.create_time<#{beforecreatetime}"));
        assertTrue(sql.contains("d.create_time=#{beforecreatetime} and d.id<#{beforeid}"));
        assertTrue(sql.contains("order by d.create_time desc, d.id desc"));
        assertTrue(sql.contains("limit #{limit}"));
    }

    @Test
    void callerAndTargetMustBothBeCanonicalWritableTaskMembers() throws Exception {
        String sql = sql("selectMailboxPage");

        assertTrue(sql.contains("inner join agent_task_member caller"));
        assertTrue(sql.contains("caller.agent_id=#{calleragentid}"));
        assertTrue(sql.contains("cast(caller.agent_id as binary)=cast(#{calleragentid} as binary)"));
        assertTrue(sql.contains("inner join agent_task_member target"));
        assertTrue(sql.contains("target.agent_id=d.target_agent_id"));
        for (String alias : new String[] {"caller", "target"}) {
            for (String column : new String[] {"member_status", "member_role", "assignment_source"}) {
                assertTrue(sql.contains("cast(" + alias + "." + column + " as binary)"),
                        alias + "." + column);
                assertTrue(sql.contains("octet_length(" + alias + "." + column + ")"),
                        alias + "." + column);
            }
        }
        assertTrue(sql.contains("cast('accepted' as binary)"));
        assertTrue(sql.contains("cast('working' as binary)"));
        assertTrue(sql.contains("cast('blocked' as binary)"));
    }

    @Test
    void defaultExcludesTerminalAndSelectListContainsNoSensitiveInternals() throws Exception {
        String sql = sql("selectMailboxPage");
        String select = sql.substring(0, sql.indexOf(" from agent_command_delivery"));

        assertTrue(sql.contains("#{includeterminal}=true"));
        for (String terminal : new String[] {
                "'succeeded'", "'failed'", "'rejected'", "'expired'", "'dead'"}) {
            assertTrue(sql.contains(terminal), terminal);
        }
        for (String forbidden : new String[] {
                "command_payload", "command_payload_hash", "lease_owner", "lease_until",
                "active_message_id", "active_attempt", "replay_parent_message_id",
                "replay_requester_id", "replay_approver_id", "replay_reason",
                "publisher_confirm_status", "mandatory_return_status", "last_error"}) {
            assertFalse(select.contains(forbidden), forbidden + ": " + select);
        }
    }

    @Test
    void shadowPromotionUsesBoundedDeliveryThenOutboxLocks() throws Exception {
        String delivery = selectSql("selectDeliveryForUpdate");
        String outbox = selectSql("selectActiveOutboxesForUpdate");

        assertTrue(delivery.contains("from agent_command_delivery"));
        assertTrue(delivery.contains("limit 1 for update"));
        assertTrue(outbox.contains("from agent_outbox_event"));
        assertTrue(outbox.contains("delivery_id=#{deliveryid}"));
        assertTrue(outbox.contains("message_id=#{messageid}"));
        assertTrue(outbox.contains("order by id"));
        assertTrue(outbox.contains("limit 2 for update"));
    }

    @Test
    void shadowPromotionCasRequiresExactIdentityMarkerStatusAndVersion() throws Exception {
        String delivery = updateSql("promoteShadowDelivery");
        String outbox = updateSql("promoteShadowOutbox");

        for (String required : new String[] {
                "id=#{delivery.id}", "command_id=#{delivery.commandid}",
                "active_message_id=#{delivery.activemessageid}", "status='dead'",
                "last_error=#{delivery.lasterror}",
                "active_attempt=#{delivery.activeattempt}",
                "attempt_count=#{delivery.attemptcount}", "version=#{delivery.version}"}) {
            assertTrue(delivery.contains(required), required + ": " + delivery);
        }
        for (String required : new String[] {
                "id=#{outbox.id}", "event_id=#{outbox.eventid}",
                "message_id=#{outbox.messageid}", "command_id=#{outbox.commandid}",
                "delivery_id=#{outbox.deliveryid}", "status='dead'",
                "last_error=#{outbox.lasterror}",
                "active_attempt=#{outbox.activeattempt}",
                "attempt_count=#{outbox.attemptcount}", "version=#{outbox.version}"}) {
            assertTrue(outbox.contains(required), required + ": " + outbox);
        }
        assertTrue(delivery.contains("set status='pending', last_error=#{marker}"));
        assertTrue(outbox.contains("set status='pending', last_error=#{marker}"));
    }

    private static String sql(String methodName) throws Exception {
        return selectSql(methodName);
    }

    private static String selectSql(String methodName) throws Exception {
        Method method = null;
        for (Method candidate : AgentCommandTransportMapper.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) method = candidate;
        }
        if (method == null) throw new NoSuchMethodException(methodName);
        Select select = method.getAnnotation(Select.class);
        if (select == null) throw new IllegalStateException(methodName + " is not @Select");
        return normalize(select.value());
    }

    private static String updateSql(String methodName) throws Exception {
        Method method = null;
        for (Method candidate : AgentCommandTransportMapper.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) method = candidate;
        }
        if (method == null) throw new NoSuchMethodException(methodName);
        Update update = method.getAnnotation(Update.class);
        if (update == null) throw new IllegalStateException(methodName + " is not @Update");
        return normalize(update.value());
    }

    private static String normalize(String[] sql) {
        return String.join(" ", sql)
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static void assertExactParameter(
            String sql, String column, String parameter) {
        assertTrue(sql.contains("cast(" + column + " as binary)=cast(#{"
                + parameter + "} as binary)"), column);
        assertTrue(sql.contains("octet_length(" + column + ")=octet_length(#{"
                + parameter + "})"), column);
    }
}
