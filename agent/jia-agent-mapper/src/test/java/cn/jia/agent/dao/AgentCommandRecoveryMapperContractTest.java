package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentCommandRecoveryMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandRecoveryMapperContractTest {
    @Test
    void discoveryIsBoundedUnlockedAndExactScoped() throws Exception {
        String reconnect = sql("selectReconnectCandidates");
        assertTrue(reconnect.contains("limit #{limit}"));
        assertTrue(reconnect.contains("order by id asc"));
        assertFalse(reconnect.contains("for update"));
        for (String column : new String[] {"tenant_id", "client_id", "target_agent_id", "status"}) {
            assertBinary(reconnect, column);
        }
        String due = sql("selectDueCandidates");
        assertTrue(due.contains("next_retry_at<=#{now}"));
        assertTrue(due.contains("status='sent'"));
        assertTrue(due.contains("update_time<=#{sentbefore}"));
        assertTrue(due.contains("expires_at<=#{now}"));
        assertTrue(due.contains("id>#{afterdeliveryid}"));
        assertFalse(due.contains("for update"));
    }

    @Test
    void locksFollowDeliveryOutboxInboxAndUseExactPredicates() throws Exception {
        for (String method : new String[] {
                "selectDeliveryForUpdate", "selectDeliveryByCommandForUpdate",
                "selectActiveOutboxesForUpdate", "selectPreviousAttemptOutboxesForUpdate",
                "selectInboxForUpdate"}) {
            String sql = sql(method);
            assertTrue(sql.endsWith("for update"), method + ": " + sql);
            assertBinary(sql, "tenant_id");
            assertBinary(sql, "client_id");
        }
        assertBinary(sql("selectDeliveryByCommandForUpdate"), "command_id");
        assertBinary(sql("selectActiveOutboxesForUpdate"), "message_id");
        assertTrue(sql("selectActiveOutboxesForUpdate").contains("limit 2"));
        String previous = sql("selectPreviousAttemptOutboxesForUpdate");
        assertTrue(previous.contains("active_attempt=#{previousattempt}"));
        assertTrue(previous.contains("limit 2"));
        assertBinary(sql("selectInboxForUpdate"), "consumer_name");
        assertBinary(sql("selectInboxForUpdate"), "message_id");
    }

    @Test
    void reissueAndAckCasFenceActiveIdentityVersionAndNoByteRewrite() throws Exception {
        String reissue = sql("reissueDelivery");
        for (String token : new String[] {
                "version=#{delivery.version}", "active_message_id=#{delivery.activemessageid}",
                "active_attempt=#{delivery.activeattempt}", "attempt_count=#{delivery.attemptcount}",
                "#{delivery.nextretryat}", "status=#{delivery.status}"}) {
            assertTrue(reissue.contains(token), token + ": " + reissue);
        }
        assertFalse(reissue.contains("command_payload="));
        assertFalse(reissue.contains("command_payload_hash="));

        String ack = sql("advanceAck");
        for (String token : new String[] {
                "version=#{delivery.version}", "active_message_id=#{delivery.activemessageid}",
                "active_attempt=#{delivery.activeattempt}", "status=#{delivery.status}"}) {
            assertTrue(ack.contains(token), token + ": " + ack);
        }
    }

    @Test
    void recoveryExhaustionAtomicallyFencesDeliveryAndInboxForManualTakeover()
            throws Exception {
        String delivery = sql("failRecoveryDelivery");
        for (String token : new String[] {"status='failed'",
                "version=#{delivery.version}",
                "active_message_id=#{delivery.activemessageid}",
                "active_attempt=#{delivery.activeattempt}",
                "attempt_count=#{delivery.attemptcount}", "status=#{delivery.status}"}) {
            assertTrue(delivery.contains(token), token + ": " + delivery);
        }
        assertFalse(delivery.contains("command_payload="));
        assertFalse(delivery.contains("set target_agent_id"));

        String inbox = sql("failRecoveryInbox");
        for (String token : new String[] {"status='failed'", "result_status='failed'",
                "version=#{inbox.version}", "active_attempt=#{inbox.activeattempt}",
                "attempt_count=#{inbox.attemptcount}", "status=#{inbox.status}",
                "result_status=#{inbox.resultstatus}"}) {
            assertTrue(inbox.contains(token), token + ": " + inbox);
        }
        for (String column : new String[] {"tenant_id", "client_id", "consumer_name",
                "message_id", "event_id", "command_id", "status", "result_status"}) {
            assertBinary(inbox, column);
        }
    }

    @Test
    void expiryMutationsFenceBothDeliveryAndWaitingInbox() throws Exception {
        String delivery = sql("expireDelivery");
        for (String token : new String[] {"version=#{delivery.version}",
                "active_message_id=#{delivery.activemessageid}",
                "active_attempt=#{delivery.activeattempt}", "status=#{delivery.status}"}) {
            assertTrue(delivery.contains(token), token + ": " + delivery);
        }
        String inbox = sql("expireWaitingInbox");
        for (String token : new String[] {"version=#{inbox.version}",
                "active_attempt=#{inbox.activeattempt}", "status='waiting_agent'",
                "result_status='waiting_agent'"}) {
            assertTrue(inbox.contains(token), token + ": " + inbox);
        }
        assertBinary(inbox, "tenant_id");
        assertBinary(inbox, "client_id");
        assertBinary(inbox, "consumer_name");
        assertBinary(inbox, "message_id");
    }

    @Test
    void newOutboxInsertKeepsBinaryWireAndReplayAuditWithoutSchemaChanges() throws Exception {
        String insert = sql("insertOutbox");
        for (String column : new String[] {
                "message_id", "event_id", "command_id", "wire_payload", "wire_payload_hash",
                "attempt_count", "active_attempt", "replay_parent_message_id",
                "replay_requester_id", "replay_reason"}) {
            assertTrue(insert.contains(column), column);
        }
        assertFalse(insert.contains("alter table"));
        assertFalse(insert.contains("json"));
    }

    private static String sql(String methodName) throws Exception {
        Method method = null;
        for (Method candidate : AgentCommandRecoveryMapper.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) method = candidate;
        }
        if (method == null) throw new NoSuchMethodException(methodName);
        Select select = method.getAnnotation(Select.class);
        Update update = method.getAnnotation(Update.class);
        Insert insert = method.getAnnotation(Insert.class);
        String[] value = select != null ? select.value() : update != null ? update.value() : insert.value();
        return String.join(" ", value).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static void assertBinary(String sql, String column) {
        assertTrue(sql.contains("cast(" + column + " as binary)"), column + ": " + sql);
        assertTrue(sql.contains("octet_length(" + column + ")"), column + ": " + sql);
    }
}
