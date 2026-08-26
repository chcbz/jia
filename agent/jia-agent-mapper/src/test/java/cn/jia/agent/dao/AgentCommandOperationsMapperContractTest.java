package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentCommandOperationsMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandOperationsMapperContractTest {
    @Test
    void readsAreByteExactBoundedStableAndPayloadRedacted() throws Exception {
        for (String methodName : new String[] {"listDlq", "listAudit"}) {
            Method method = Arrays.stream(AgentCommandOperationsMapper.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            Select select = method.getAnnotation(Select.class);
            assertNotNull(select, methodName);
            String sql = normalize(select.value());
            if (methodName.equals("listDlq")) {
                assertTrue(sql.contains("cast(d.tenant_id as binary)=cast(#{tenantid} as binary)"));
                assertTrue(sql.contains("octet_length(d.tenant_id)=octet_length(#{tenantid})"));
                assertTrue(sql.contains("cast(d.client_id as binary)=cast(#{clientid} as binary)"));
                assertTrue(sql.contains("octet_length(d.client_id)=octet_length(#{clientid})"));
            } else {
                assertTrue(sql.contains("cast(tenant_id as binary)=cast(#{tenantid} as binary)"));
                assertTrue(sql.contains("octet_length(tenant_id)=octet_length(#{tenantid})"));
                assertTrue(sql.contains("cast(client_id as binary)=cast(#{clientid} as binary)"));
                assertTrue(sql.contains("octet_length(client_id)=octet_length(#{clientid})"));
            }
            assertTrue(sql.contains("order by ") && sql.contains(" limit #{limit}"), sql);
            for (String forbidden : new String[] {
                    "wire_payload as", "command_payload as", "lease_owner as",
                    "lease_until as", "return_reply_text as", "confirm_error as"}) {
                assertFalse(sql.contains(forbidden), methodName + ": " + forbidden);
            }
        }
    }

    @Test
    void dlqCountAndPageExposeOnlyExactDurableBrokerRedriveCandidates() throws Exception {
        for (String methodName : new String[] {"countDlq", "listDlq"}) {
            String sql = select(methodName);
            for (String predicate : new String[] {
                    "d.status='published'", "d.next_retry_at is null",
                    "d.active_attempt>0", "d.expires_at>#{now}", "d.lease_owner is null",
                    "d.lease_until is null", "o.status='published'",
                    "o.attempt_count>0", "o.next_retry_at is null",
                    "o.lease_owner is null", "o.lease_until is null",
                    "o.active_attempt=d.active_attempt", "o.expires_at=d.expires_at",
                    "o.command_id=d.command_id", "o.aggregate_type='task'",
                    "o.aggregate_id=d.task_id", "o.publisher_confirm_status='ack'",
                    "o.confirmed_at is not null", "o.confirmed_at>0", "o.confirm_error is null",
                    "o.mandatory_return_status='not_returned'", "o.returned_at is null",
                    "o.return_reply_code is null", "o.return_reply_text is null",
                    "o.published_at is not null", "o.published_at>0",
                    "o.last_error is null", "i.id is null"}) {
                assertTrue(sql.contains(predicate), methodName + ": " + predicate);
            }
            assertTrue(sql.contains("cast(d.tenant_id as binary)=cast(#{tenantid} as binary)"));
            assertTrue(sql.contains("octet_length(d.tenant_id)=octet_length(#{tenantid})"));
            assertTrue(sql.contains("cast(d.client_id as binary)=cast(#{clientid} as binary)"));
            assertTrue(sql.contains("octet_length(d.client_id)=octet_length(#{clientid})"));
            assertTrue(sql.contains("i.consumer_name='agent-command-dispatch-v1'"));
            assertFalse(sql.contains("status in ('dead','failed')"), sql);
            assertFalse(sql.contains("i.status='dead'"), sql);
            assertFalse(sql.contains("i.result_status='dead'"), sql);
        }
    }

    @Test
    void operationAuditMapperIsInsertOnlyAndContainsNoPayloadOrCredentialColumn() {
        for (Method method : AgentCommandOperationsMapper.class.getMethods()) {
            assertFalse(method.isAnnotationPresent(Update.class), method.getName());
        }
        Method insert = Arrays.stream(AgentCommandOperationsMapper.class.getMethods())
                .filter(method -> method.getName().equals("insertAudit"))
                .findFirst().orElseThrow();
        String sql = normalize(insert.getAnnotation(Insert.class).value());
        assertTrue(sql.startsWith("insert into agent_command_operation_audit"));
        for (String forbidden : new String[] {
                "wire_payload", "command_payload", "headers", "password", "credential",
                "lease_owner", "lease_until"}) {
            assertFalse(sql.contains(forbidden), forbidden);
        }
    }

    @Test
    void operationLocksFreezeDeliveryThenActiveOutboxThenInboxSelectors() throws Exception {
        String delivery = select("lockDelivery");
        String outbox = select("lockActiveOutboxes");
        String inbox = select("lockInbox");
        assertTrue(delivery.contains("from agent_command_delivery") && delivery.endsWith("for update"));
        assertTrue(outbox.contains("from agent_outbox_event") && outbox.endsWith("for update"));
        assertTrue(inbox.contains("from agent_consumer_inbox") && inbox.endsWith("for update"));
    }

    @Test
    void operationMetricsRetainRequestedRowsSoIncompleteResultsRemainObservable() throws Exception {
        String outcomes = select("countOperationOutcomes");
        assertTrue(outcomes.contains("concat(operation_type,':',outcome)"));
        assertFalse(outcomes.contains("phase='result'"));
        assertTrue(outcomes.contains("group by operation_type,outcome"));
    }

    private String select(String name) throws Exception {
        Method method = Arrays.stream(AgentCommandOperationsMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        return normalize(method.getAnnotation(Select.class).value());
    }

    private String normalize(String[] sql) {
        return String.join(" ", sql).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
