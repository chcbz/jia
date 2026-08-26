package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.AgentCommandOperationsDaoImpl;
import cn.jia.agent.entity.AgentCommandRedriveOperationState;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
            Update update = method.getAnnotation(Update.class);
            if (update != null) {
                assertTrue(normalize(update.value()).startsWith(
                        "update agent_command_redrive_operation"), method.getName());
            }
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
    void redriveReservationInsertIsPendingOnlyPayloadFreeAndGeneratedGuardFree() throws Exception {
        Method method = method("insertPendingRedriveOperation");
        String sql = normalize(method.getAnnotation(Insert.class).value());
        assertTrue(sql.startsWith("insert into agent_command_redrive_operation"));
        assertTrue(sql.contains("'pending','pending',null,#{requestedat},null,0"));
        assertTrue(sql.endsWith(
                "#{tenantid},#{clientid},#{requestedat},#{requestedat})"), sql);
        for (String forbidden : new String[] {
                "wire_payload", "command_payload", "headers", "disposition_guard,", "redrive_guard,"}) {
            assertFalse(sql.contains(forbidden), forbidden);
        }
    }

    @Test
    void redriveLocksAreExactScopedStableBoundedAndForUpdate() throws Exception {
        String byOperation = select("lockRedriveOperation");
        assertExactScope(byOperation);
        assertTrue(byOperation.contains(
                "cast(operation_id as binary)=cast(#{operationid} as binary)"));
        assertTrue(byOperation.endsWith("limit 1 for update"), byOperation);

        String active = select("lockActiveRedriveOperations");
        assertExactScope(active);
        assertTrue(active.contains("delivery_id=#{deliveryid}"));
        assertTrue(active.contains("source_attempt=#{sourceattempt}"));
        assertTrue(active.contains(
                "cast(source_message_id as binary)=cast(#{sourcemessageid} as binary)"));
        assertTrue(active.contains("disposition_guard=1"));
        assertTrue(active.endsWith("order by id asc limit 2 for update"), active);

        String recovery = select("lockPendingRedriveOperations");
        assertExactScope(recovery);
        assertTrue(recovery.contains(
                "outcome_state='pending' and settlement_state='pending'"));
        assertTrue(recovery.contains("requested_at<=#{requestedbefore} and id>#{afterid}"));
        assertTrue(recovery.endsWith("order by id asc limit #{limit} for update"), recovery);
    }

    @Test
    void redriveTerminalCasRequiresPendingVersionAndExactLegalPairs() throws Exception {
        Method method = method("compareAndSetRedriveOperationTerminal");
        String sql = normalize(method.getAnnotation(Update.class).value());
        assertTrue(sql.startsWith("update agent_command_redrive_operation"));
        assertExactScope(sql);
        assertTrue(sql.contains("outcome_state='pending' and settlement_state='pending'"));
        assertTrue(sql.contains("version=#{expectedversion}"));
        assertTrue(sql.contains("version=version+1"));
        assertTrue(sql.contains(
                "#{outcomestate}='succeeded' and #{settlementstate}='source_acked' and #{errorcode} is null"));
        assertTrue(sql.contains(
                "#{outcomestate}='failed' and #{settlementstate} in ('source_requeued','not_acquired','unknown')"));
        assertTrue(sql.contains(
                "#{errorcode} is not null and char_length(#{errorcode}) between 1 and 200"));
        assertFalse(sql.contains("agent_command_operation_audit"));
    }

    @Test
    void daoTerminalProjectionRejectsPendingAndPassesExactEnumNames() {
        AgentCommandOperationsMapper mapper = mock(AgentCommandOperationsMapper.class);
        AgentCommandOperationsDaoImpl dao = new AgentCommandOperationsDaoImpl(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> dao.compareAndSetRedriveOperationTerminal(
                        "tenant-a", "client-a", "operation-1",
                        AgentCommandRedriveOperationState.PENDING, null, 10, 0));

        assertThrows(IllegalArgumentException.class,
                () -> dao.lockPendingRedriveOperations(
                        "tenant-a", "client-a", 10, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> dao.lockPendingRedriveOperations(
                        "tenant-a", "client-a", 10, 0, 101));

        dao.compareAndSetRedriveOperationTerminal(
                "tenant-a", "client-a", "operation-1",
                AgentCommandRedriveOperationState.FAILED_UNKNOWN,
                "SETTLEMENT_UNKNOWN", 10, 0);
        verify(mapper).compareAndSetRedriveOperationTerminal(
                "tenant-a", "client-a", "operation-1", "FAILED", "UNKNOWN",
                "SETTLEMENT_UNKNOWN", 10, 0);
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
        return normalize(method(name).getAnnotation(Select.class).value());
    }

    private Method method(String name) {
        return Arrays.stream(AgentCommandOperationsMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
    }

    private void assertExactScope(String sql) {
        assertTrue(sql.contains("cast(tenant_id as binary)=cast(#{tenantid} as binary)"), sql);
        assertTrue(sql.contains("octet_length(tenant_id)=octet_length(#{tenantid})"), sql);
        assertTrue(sql.contains("cast(client_id as binary)=cast(#{clientid} as binary)"), sql);
        assertTrue(sql.contains("octet_length(client_id)=octet_length(#{clientid})"), sql);
    }

    private String normalize(String[] sql) {
        return String.join(" ", sql).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
