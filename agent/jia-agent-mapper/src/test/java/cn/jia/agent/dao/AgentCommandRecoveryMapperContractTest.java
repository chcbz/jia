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
    void discoveryIsBoundedUnlockedAndSeparatedByProtocolLane() throws Exception {
        for (String method : new String[] {
                "selectV2ReconnectCandidates", "selectV2DueCandidates"}) {
            String sql = sql(method);
            assertTrue(sql.contains("protocol_version=2"), method);
            assertFalse(sql.contains("for update"), method);
            assertTrue(sql.indexOf("protocol_version=2") < sql.indexOf("order by id asc"), method);
            assertTrue(sql.indexOf("order by id asc") < sql.indexOf("limit #{limit}"), method);
            for (String column : new String[] {
                    "tenant_id", "data_domain_id", "execution_pool_id",
                    "producer_deployment_id", "module_id"}) {
                assertBinary(sql, column);
            }
            for (String token : new String[] {
                    "admission_epoch=#{admissionepoch}",
                    "migration_epoch=#{migrationepoch}"}) {
                assertTrue(sql.contains(token), method + ": " + token);
            }
        }

        String reconnect = sql("selectV2ReconnectCandidates");
        assertBinary(reconnect, "client_id");
        assertBinary(reconnect, "target_agent_id");

        String due = sql("selectV2DueCandidates");
        assertTrue(due.contains("next_retry_at<=#{now}"));
        assertTrue(due.contains("or (status='retry' and ("));
        assertTrue(due.contains("status='sent'"));
        assertTrue(due.contains("update_time<=#{sentbefore}"));
        assertTrue(due.contains("expires_at<=#{now}"));
        assertTrue(due.contains("id>#{afterdeliveryid}"));

        for (String method : new String[] {
                "selectLegacyReconnectCandidates", "selectLegacyDueCandidates"}) {
            String sql = sql(method);
            assertTrue(sql.contains("tenant_id='0'"), method);
            assertTrue(sql.contains("protocol_version is null or protocol_version=1"), method);
            for (String column : new String[] {
                    "data_domain_id", "execution_pool_id", "admission_epoch",
                    "producer_deployment_id", "module_id", "migration_epoch"}) {
                assertTrue(sql.contains(column + " is null"), method + ": " + column);
            }
            assertFalse(sql.contains("for update"), method);
            assertTrue(sql.indexOf("tenant_id='0'") < sql.indexOf("order by id asc"), method);
            assertTrue(sql.indexOf("order by id asc") < sql.indexOf("limit #{limit}"), method);
        }
        assertBinary(sql("selectLegacyReconnectCandidates"), "client_id");
        assertBinary(sql("selectLegacyReconnectCandidates"), "target_agent_id");
        assertFalse(sql("selectLegacyDueCandidates").contains("or (status='retry' and ("));
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

        String retryInbox = sql("expireRetryInbox");
        for (String token : new String[] {"version=#{inbox.version}",
                "active_attempt=#{inbox.activeattempt}", "status='retry'",
                "result_status='retry'", "next_retry_at=#{inbox.nextretryat}"}) {
            assertTrue(retryInbox.contains(token), token + ": " + retryInbox);
        }
        assertBinary(retryInbox, "tenant_id");
        assertBinary(retryInbox, "client_id");
        assertBinary(retryInbox, "consumer_name");
        assertBinary(retryInbox, "message_id");
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


    @Test
    void protocolV2RecoveryColumnsInsertHintAndCasRemainFenced() throws Exception {
        String deliveryColumns = AgentCommandRecoveryMapper.DELIVERY_COLUMNS.toLowerCase(Locale.ROOT);
        for (String column : new String[] {
                "protocol_version", "data_domain_id", "execution_pool_id", "admission_epoch",
                "producer_deployment_id", "module_id", "migration_epoch", "owner_jiacn"}) {
            assertTrue(deliveryColumns.contains(column), column + ": " + deliveryColumns);
        }
        String outboxColumns = AgentCommandRecoveryMapper.OUTBOX_COLUMNS.toLowerCase(Locale.ROOT);
        for (String column : new String[] {
                "protocol_version", "data_domain_id", "execution_pool_id", "admission_epoch",
                "producer_deployment_id", "module_id", "migration_epoch"}) {
            assertTrue(outboxColumns.contains(column), column + ": " + outboxColumns);
        }

        String hint = sql("selectDeliveryProtocolHint");
        assertFalse(hint.contains("for update"), hint);
        assertTrue(hint.contains("limit 1"), hint);

        String insert = sql("insertOutbox");
        for (String token : new String[] {
                "protocol_version", "#{protocolversion}",
                "data_domain_id", "#{datadomainid}",
                "execution_pool_id", "#{executionpoolid}",
                "admission_epoch", "#{admissionepoch}",
                "producer_deployment_id", "#{producerdeploymentid}",
                "module_id", "#{moduleid}",
                "migration_epoch", "#{migrationepoch}"}) {
            assertTrue(insert.contains(token), token + ": " + insert);
        }

        for (String method : new String[] {"reissueDelivery", "manualReissueDelivery"}) {
            String mutation = sql(method);
            for (String token : new String[] {
                    "protocol_version", "#{delivery.protocolversion}",
                    "data_domain_id", "#{delivery.datadomainid}",
                    "execution_pool_id", "#{delivery.executionpoolid}",
                    "admission_epoch", "#{delivery.admissionepoch}",
                    "producer_deployment_id", "#{delivery.producerdeploymentid}",
                    "module_id", "#{delivery.moduleid}",
                    "migration_epoch", "#{delivery.migrationepoch}"}) {
                assertTrue(mutation.contains(token), method + " " + token + ": " + mutation);
            }
        }
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
