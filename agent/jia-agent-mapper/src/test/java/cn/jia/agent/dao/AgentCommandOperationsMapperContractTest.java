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
    void auditReadIsByteExactBoundedStableAndPayloadRedacted() throws Exception {
        String sql = select("listAudit");
        assertTrue(sql.contains("cast(tenant_id as binary)=cast(#{tenantid} as binary)"));
        assertTrue(sql.contains("octet_length(tenant_id)=octet_length(#{tenantid})"));
        assertTrue(sql.contains("cast(client_id as binary)=cast(#{clientid} as binary)"));
        assertTrue(sql.contains("octet_length(client_id)=octet_length(#{clientid})"));
        assertTrue(sql.contains("order by id asc limit #{limit}"), sql);
        for (String forbidden : new String[] {
                "wire_payload as", "command_payload as", "lease_owner as",
                "lease_until as", "return_reply_text as", "confirm_error as"}) {
            assertFalse(sql.contains(forbidden), forbidden);
        }
    }

    @Test
    void operationV1StatusReadIsByteExactPrincipalScopedBoundedAndPayloadFree() throws Exception {
        String sql = select("findOperationStatusRows");
        assertExactScope(sql);
        assertTrue(sql.contains(
                "cast(requester_id as binary)=cast(#{requesterid} as binary)"), sql);
        assertTrue(sql.contains(
                "octet_length(requester_id)=octet_length(#{requesterid})"), sql);
        assertTrue(sql.contains(
                "cast(operation_id as binary)=cast(#{operationid} as binary)"), sql);
        assertTrue(sql.contains(
                "octet_length(operation_id)=octet_length(#{operationid})"), sql);
        assertTrue(sql.endsWith("order by id asc limit 3"), sql);
        assertFalse(sql.contains("for update"), sql);
        for (String forbidden : new String[] {
                "wire_payload", "command_payload", "wire_hash", "task_id", "target_agent_id",
                "command_id", "approver_id", "reason", "ticket_reference", "created_by"}) {
            assertFalse(sql.contains(forbidden), forbidden + ": " + sql);
        }
    }

    @Test
    void dlqDiscoveryIsDeliveryOnlyBroadBoundedStableAndHasNoMinAuthorization() throws Exception {
        String count = select("countDlqBroad");
        String page = select("listDlqBroad");
        for (String sql : new String[] {count, page}) {
            assertTrue(sql.contains("from agent_command_delivery"), sql);
            assertExactScope(sql);
            for (String predicate : new String[] {
                    "status='published'", "next_retry_at is null", "active_attempt>0",
                    "expires_at>#{now}", "lease_owner is null", "lease_until is null"}) {
                assertTrue(sql.contains(predicate), predicate + ": " + sql);
            }
            assertFalse(sql.contains(" join "), sql);
            assertFalse(sql.contains("min("), sql);
            assertFalse(sql.contains("agent_outbox_event"), sql);
            assertFalse(sql.contains("agent_consumer_inbox"), sql);
        }
        assertTrue(page.contains("id>#{afterdeliveryid}"), page);
        assertTrue(page.endsWith("order by id asc limit #{limit}"), page);
    }

    @Test
    void bundleSelectorsAreExactScopedBoundedAndLockVariantsMatchFrozenOrder() throws Exception {
        for (String methodName : new String[] {
                "selectActiveOutboxes", "selectCurrentAttemptOutboxes",
                "selectPreviousAttemptOutboxes", "selectActiveRedriveOperations"}) {
            String sql = select(methodName);
            assertExactScope(sql);
            assertTrue(sql.endsWith("order by id asc limit 2"), methodName + ": " + sql);
            assertFalse(sql.contains("for update"), methodName);
        }
        String inbox = select("selectInbox");
        assertExactScope(inbox);
        assertTrue(inbox.endsWith("limit 1"), inbox);
        assertFalse(inbox.contains("for update"), inbox);

        for (String methodName : new String[] {
                "lockActiveOutboxes", "lockCurrentAttemptOutboxes",
                "lockPreviousAttemptOutboxes", "lockActiveRedriveOperations"}) {
            String sql = select(methodName);
            assertExactScope(sql);
            assertTrue(sql.endsWith("order by id asc limit 2 for update"),
                    methodName + ": " + sql);
        }
        assertTrue(select("lockInbox").endsWith("limit 1 for update"));
        assertTrue(select("selectActiveRedriveOperations").contains("redrive_guard=1"));
        assertTrue(select("lockActiveRedriveOperations").contains("redrive_guard=1"));
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
        assertTrue(active.contains("redrive_guard=1"));
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
    void operationLocksFreezeDeliveryThenActiveCurrentPreviousInboxAndBlockerSelectors() throws Exception {
        String delivery = select("lockDelivery");
        String active = select("lockActiveOutboxes");
        String current = select("lockCurrentAttemptOutboxes");
        String previous = select("lockPreviousAttemptOutboxes");
        String inbox = select("lockInbox");
        String blocker = select("lockActiveRedriveOperations");
        assertTrue(delivery.contains("from agent_command_delivery") && delivery.endsWith("for update"));
        assertTrue(active.contains("from agent_outbox_event") && active.endsWith("for update"));
        assertTrue(current.contains("active_attempt=#{activeattempt}") && current.endsWith("for update"));
        assertTrue(previous.contains("active_attempt=#{previousattempt}") && previous.endsWith("for update"));
        assertTrue(inbox.contains("from agent_consumer_inbox") && inbox.endsWith("for update"));
        assertTrue(blocker.contains("redrive_guard=1") && blocker.endsWith("for update"));
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
