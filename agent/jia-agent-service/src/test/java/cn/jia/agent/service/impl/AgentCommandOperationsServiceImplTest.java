package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentCommandOperationsProperties;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationsException;
import cn.jia.agent.entity.AgentCommandMetricCount;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandDlqRedriver;
import cn.jia.agent.service.AgentCommandReissueService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandOperationsServiceImplTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long SERVICE_NOW = NOW + 500L;
    private static final long ISSUED = NOW - 1_000L;
    private static final long EXPIRES = ISSUED + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS;
    private static final String MESSAGE = "11111111-1111-1111-1111-111111111111";
    private static final String INTENT = "intent-d09-operations";
    private static final String COMMAND = AgentCommandCanonicalCodec.hallCommandId(
            "tenant-a", "client-a", "task-1", "agent-a", INTENT,
            AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);

    @Test
    void brokerRedriveCommitsRequestAuditBeforeTransactionFreeExactBrokerPublish() {
        Fixture fixture = fixture();
        List<String> order = new ArrayList<>();
        doAnswer(invocation -> {
            AgentCommandOperationAuditEntity audit = invocation.getArgument(0);
            audit.setId(fixture.ids.incrementAndGet());
            order.add("audit:" + audit.getPhase());
            return 1;
        }).when(fixture.dao).insertAudit(any());
        AgentCommandDlqRedriver redriver = (request, timeout, scanLimit) -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            order.add("broker");
            assertEquals(MESSAGE, request.messageId());
            assertArrayEquals(fixture.outbox.getWirePayload(), request.wirePayload());
            assertEquals(100, scanLimit);
            return AgentRabbitPublishResult.ack();
        };
        AgentCommandOperationsServiceImpl service = service(fixture, redriver, null, true, false);

        var result = service.brokerRedrive(request(null), NOW);

        assertEquals("SUCCEEDED", result.outcome());
        assertEquals(NOW + 500L, result.completedAt());
        assertEquals(List.of("audit:REQUEST", "broker", "audit:RESULT"), order);
        ArgumentCaptor<AgentCommandOperationAuditEntity> audits =
                ArgumentCaptor.forClass(AgentCommandOperationAuditEntity.class);
        verify(fixture.dao, org.mockito.Mockito.times(2)).insertAudit(audits.capture());
        assertArrayEquals(fixture.outbox.getWirePayloadHash(),
                audits.getAllValues().getFirst().getWireHash());
        assertEquals(NOW, audits.getAllValues().getLast().getRequestedAt());
        assertEquals(NOW + 500L, audits.getAllValues().getLast().getCompletedAt());
        assertEquals("SUCCEEDED", audits.getAllValues().getLast().getOutcome());
    }

    @Test
    void listedBrokerDlqCandidateUsesSamePolicyAndIsRedriveable() {
        Fixture fixture = fixture();
        when(fixture.dao.listDlqBroad("tenant-a", "client-a", 0, SERVICE_NOW, 101))
                .thenReturn(List.of(fixture.delivery));
        AgentCommandDlqRedriver redriver = (request, timeout, scanLimit) ->
                AgentRabbitPublishResult.ack();
        AgentCommandOperationsServiceImpl service = service(
                fixture, redriver, null, true, false);

        var page = service.listDlq("tenant-a", "client-a", 0, 10);
        var redriven = service.brokerRedrive(request(null), NOW);

        assertEquals(1, page.items().size());
        assertEquals(1L, page.nextAfterDeliveryId());
        assertFalse(page.hasMore());
        assertEquals("PUBLISHED", page.items().getFirst().deliveryStatus());
        assertEquals("SUCCEEDED", redriven.outcome());
        verify(fixture.dao).listDlqBroad("tenant-a", "client-a", 0, SERVICE_NOW, 101);
    }

    @Test
    void queryVisibleButPolicyIllegalRowsAreFilteredWithExaminedCursor() {
        Fixture fixture = fixture();
        fixture.outbox.setRoutingKey("agent.command.poison");
        when(fixture.dao.listDlqBroad("tenant-a", "client-a", 0, SERVICE_NOW, 101))
                .thenReturn(List.of(fixture.delivery));

        var page = service(fixture, null, null, false, false)
                .listDlq("tenant-a", "client-a", 0, 10);

        assertTrue(page.items().isEmpty());
        assertEquals(1L, page.nextAfterDeliveryId());
        assertFalse(page.hasMore());
    }

    @Test
    void dlqScanBudgetAndLimitUseLastExaminedCursorAndCoarseHasMore() {
        Fixture limited = fixture();
        AgentCommandDeliveryEntity second = broadDelivery(2L);
        when(limited.dao.listDlqBroad("tenant-a", "client-a", 0, SERVICE_NOW, 101))
                .thenReturn(List.of(limited.delivery, second));
        var first = service(limited, null, null, false, false)
                .listDlq("tenant-a", "client-a", 0, 1);
        assertEquals(1, first.items().size());
        assertEquals(1L, first.nextAfterDeliveryId());
        assertTrue(first.hasMore());

        Fixture budgeted = fixture();
        List<AgentCommandDeliveryEntity> broad = new ArrayList<>();
        for (long id = 1; id <= 101; id++) broad.add(broadDelivery(id));
        when(budgeted.dao.listDlqBroad("tenant-a", "client-a", 0, SERVICE_NOW, 101))
                .thenReturn(broad);
        var budgetPage = service(budgeted, null, null, false, false)
                .listDlq("tenant-a", "client-a", 0, 100);
        assertTrue(budgetPage.items().isEmpty());
        assertEquals(100L, budgetPage.nextAfterDeliveryId());
        assertTrue(budgetPage.hasMore());
    }

    @Test
    void auditPageUsesStableLimitPlusOneCursorAndHasMore() {
        Fixture fixture = fixture();
        when(fixture.dao.listAudit("tenant-a", "client-a", 0, 2))
                .thenReturn(List.of(auditEntry(1L), auditEntry(2L)));

        var page = service(fixture, null, null, false, false)
                .listAudit("tenant-a", "client-a", 0, 1);

        assertEquals(List.of(1L), page.items().stream().map(row -> row.id()).toList());
        assertEquals(1L, page.nextAfterId());
        assertTrue(page.hasMore());
        verify(fixture.dao).listAudit("tenant-a", "client-a", 0, 2);
    }

    @Test
    void missingOrCrossScopeSourceIsNonEnumeratingAuditedAndNeverTouchesBroker() {
        Fixture fixture = fixture();
        when(fixture.dao.lockDelivery("tenant-a", "client-a", 1L)).thenReturn(null);
        AgentCommandDlqRedriver redriver = mock(AgentCommandDlqRedriver.class);
        AgentCommandOperationsServiceImpl service = service(fixture, redriver, null, true, false);

        AgentCommandOperationsException failure = assertThrows(
                AgentCommandOperationsException.class,
                () -> service.brokerRedrive(request(null), NOW));

        assertEquals(AgentCommandOperationsException.Reason.NOT_FOUND_OR_FORBIDDEN,
                failure.reason());
        verify(redriver, never()).redrive(any(), anyLong(), anyInt());
        ArgumentCaptor<AgentCommandOperationAuditEntity> audits =
                ArgumentCaptor.forClass(AgentCommandOperationAuditEntity.class);
        verify(fixture.dao, org.mockito.Mockito.times(2)).insertAudit(audits.capture());
        assertEquals("REQUEST", audits.getAllValues().getFirst().getPhase());
        assertEquals("REJECTED", audits.getAllValues().getLast().getOutcome());
        assertEquals(null, audits.getAllValues().getFirst().getCommandId());
    }

    @Test
    void manualReissueRequiresDistinctApproverAndAppendsFreshIdentityResult() {
        Fixture fixture = fixture();
        fixture.delivery.setStatus("DEAD");
        AgentCommandReissueService reissue = mock(AgentCommandReissueService.class);
        when(reissue.reissueManually(any(), anyLong())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return new AgentCommandManualReissueResult(1L, COMMAND, MESSAGE,
                    "22222222-2222-2222-2222-222222222222",
                    "33333333-3333-3333-3333-333333333333", 1, 2);
        });
        AgentCommandOperationsServiceImpl service = service(fixture, null, reissue, false, true);

        var result = service.manualReissue(request("approver-b"), NOW);

        assertEquals("22222222-2222-2222-2222-222222222222", result.newMessageId());
        assertEquals(2, result.newAttempt());
        ArgumentCaptor<AgentCommandOperationAuditEntity> audits =
                ArgumentCaptor.forClass(AgentCommandOperationAuditEntity.class);
        verify(fixture.dao, org.mockito.Mockito.times(2)).insertAudit(audits.capture());
        assertEquals("approver-b", audits.getAllValues().getFirst().getApproverId());
        assertEquals(result.newMessageId(), audits.getAllValues().getLast().getNewMessageId());
    }

    @Test
    void malformedManualReissueResultIsRejectedAndNeverReportedAsSuccess() {
        Fixture fixture = fixture();
        fixture.delivery.setStatus("DEAD");
        AgentCommandReissueService reissue = mock(AgentCommandReissueService.class);
        when(reissue.reissueManually(any(), anyLong())).thenReturn(
                new AgentCommandManualReissueResult(99L, COMMAND, MESSAGE,
                        "22222222-2222-2222-2222-222222222222",
                        "33333333-3333-3333-3333-333333333333", 1, 2));

        AgentCommandOperationsException failure = assertThrows(
                AgentCommandOperationsException.class,
                () -> service(fixture, null, reissue, false, true)
                        .manualReissue(request("approver-b"), NOW));

        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                failure.reason());
        ArgumentCaptor<AgentCommandOperationAuditEntity> audits =
                ArgumentCaptor.forClass(AgentCommandOperationAuditEntity.class);
        verify(fixture.dao, org.mockito.Mockito.times(2)).insertAudit(audits.capture());
        assertEquals("REQUESTED", audits.getAllValues().getFirst().getOutcome());
        assertEquals("REJECTED", audits.getAllValues().getLast().getOutcome());
        assertEquals("REISSUE_REJECTED", audits.getAllValues().getLast().getErrorCode());
    }

    @Test
    void manualResultAuditFailureIsAuditUnavailableInsideMutationTransaction() {
        Fixture fixture = fixture();
        fixture.delivery.setStatus("DEAD");
        AgentCommandReissueService reissue = mock(AgentCommandReissueService.class);
        when(reissue.reissueManually(any(), anyLong())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return new AgentCommandManualReissueResult(1L, COMMAND, MESSAGE,
                    "22222222-2222-2222-2222-222222222222",
                    "33333333-3333-3333-3333-333333333333", 1, 2);
        });
        AtomicLong inserts = new AtomicLong();
        doAnswer(invocation -> {
            AgentCommandOperationAuditEntity row = invocation.getArgument(0);
            if (inserts.incrementAndGet() == 2) throw new IllegalStateException("db unavailable");
            row.setId(77L);
            return 1;
        }).when(fixture.dao).insertAudit(any());

        AgentCommandOperationsException failure = assertThrows(
                AgentCommandOperationsException.class,
                () -> service(fixture, null, reissue, false, true)
                        .manualReissue(request("approver-b"), NOW));

        assertEquals(AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE,
                failure.reason());
        verify(reissue).reissueManually(any(), anyLong());
        assertEquals(2L, inserts.get());
    }

    @Test
    void brokerRedriveRequiresPublishedSourceWithoutAnyDurableInboxDisposition() {
        Fixture fixture = fixture();
        AgentCommandDlqRedriver redriver = mock(AgentCommandDlqRedriver.class);
        AgentCommandOperationsServiceImpl service = service(fixture, redriver, null, true, false);
        fixture.delivery.setStatus("DEAD");

        AgentCommandOperationsException dead = assertThrows(
                AgentCommandOperationsException.class,
                () -> service.brokerRedrive(request(null), NOW));
        assertEquals(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN, dead.reason());
        verify(redriver, never()).redrive(any(), anyLong(), anyInt());

        Fixture handled = fixture();
        handled.delivery.setStatus("PUBLISHED");
        when(handled.dao.lockInbox("tenant-a", "client-a",
                "agent-command-dispatch-v1", MESSAGE)).thenReturn(
                new cn.jia.agent.entity.AgentConsumerInboxEntity().setId(9L));
        AgentCommandOperationsServiceImpl handledService = service(
                handled, redriver, null, true, false);
        AgentCommandOperationsException inbox = assertThrows(
                AgentCommandOperationsException.class,
                () -> handledService.brokerRedrive(request(null), NOW));
        assertEquals(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN, inbox.reason());
    }

    @Test
    void publishFailureAppendsFailedResultAndResultAuditFailureNeverRepublishes() {
        Fixture failed = fixture();
        AgentCommandDlqRedriver redriver = mock(AgentCommandDlqRedriver.class);
        when(redriver.redrive(any(), anyLong(), anyInt())).thenReturn(
                new AgentRabbitPublishResult(AgentRabbitPublishResult.Type.NACK,
                        "NACK", "NOT_RETURNED", null, null, "RABBIT_NACK"));
        AgentCommandOperationsServiceImpl service = service(failed, redriver, null, true, false);

        AgentCommandOperationsException nack = assertThrows(
                AgentCommandOperationsException.class,
                () -> service.brokerRedrive(request(null), NOW));
        assertEquals(AgentCommandOperationsException.Reason.PUBLISH_FAILED, nack.reason());
        var audit = org.mockito.ArgumentCaptor.forClass(AgentCommandOperationAuditEntity.class);
        verify(failed.dao, org.mockito.Mockito.times(2)).insertAudit(audit.capture());
        assertEquals("FAILED", audit.getAllValues().getLast().getOutcome());
        assertEquals("RABBIT_NACK", audit.getAllValues().getLast().getErrorCode());

        Fixture missingResult = fixture();
        AtomicLong inserts = new AtomicLong();
        doAnswer(invocation -> {
            AgentCommandOperationAuditEntity row = invocation.getArgument(0);
            if (inserts.incrementAndGet() == 2) throw new IllegalStateException("db unavailable");
            row.setId(77L);
            return 1;
        }).when(missingResult.dao).insertAudit(any());
        AgentCommandDlqRedriver ack = mock(AgentCommandDlqRedriver.class);
        when(ack.redrive(any(), anyLong(), anyInt())).thenReturn(AgentRabbitPublishResult.ack());
        AgentCommandOperationsException auditFailure = assertThrows(
                AgentCommandOperationsException.class,
                () -> service(missingResult, ack, null, true, false)
                        .brokerRedrive(request(null), NOW));
        assertEquals(AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE,
                auditFailure.reason());
        verify(ack, org.mockito.Mockito.times(1)).redrive(any(), anyLong(), anyInt());
    }

    @Test
    void metricsRejectUnknownDuplicateBudgetAndCountDriftAndExposeExactDlqCount() {
        Fixture fixture = fixture();
        when(fixture.dao.countDeliveryStatuses("tenant-a", "client-a"))
                .thenReturn(List.of(new AgentCommandMetricCount("UNKNOWN", 1)));
        AgentCommandOperationsServiceImpl service = service(fixture, null, null, false, false);
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                assertThrows(AgentCommandOperationsException.class,
                        () -> service.metrics("tenant-a", "client-a", NOW)).reason());

        Fixture duplicate = fixture();
        when(duplicate.dao.countDeliveryStatuses("tenant-a", "client-a"))
                .thenReturn(List.of(new AgentCommandMetricCount("PENDING", 1),
                        new AgentCommandMetricCount("PENDING", 2)));
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                assertThrows(AgentCommandOperationsException.class,
                        () -> service(duplicate, null, null, false, false)
                                .metrics("tenant-a", "client-a", NOW)).reason());

        Fixture overBudget = fixture();
        when(overBudget.dao.countDlqBroad("tenant-a", "client-a", NOW)).thenReturn(101L);
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                assertThrows(AgentCommandOperationsException.class,
                        () -> service(overBudget, null, null, false, false)
                                .metrics("tenant-a", "client-a", NOW)).reason());

        Fixture countDrift = fixture();
        when(countDrift.dao.countDlqBroad("tenant-a", "client-a", NOW)).thenReturn(1L);
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                assertThrows(AgentCommandOperationsException.class,
                        () -> service(countDrift, null, null, false, false)
                                .metrics("tenant-a", "client-a", NOW)).reason());

        Fixture requested = fixture();
        when(requested.dao.countDlqBroad("tenant-a", "client-a", NOW)).thenReturn(1L);
        when(requested.dao.listDlqBroad("tenant-a", "client-a", 0, NOW, 101))
                .thenReturn(List.of(requested.delivery));
        when(requested.dao.countOperationOutcomes("tenant-a", "client-a"))
                .thenReturn(List.of(new AgentCommandMetricCount(
                        "BROKER_REDRIVE:REQUESTED", 3)));
        var metrics = service(requested, null, null, false, false)
                .metrics("tenant-a", "client-a", NOW);
        assertEquals(1L, metrics.rabbitDlqCount());
        assertEquals(3L, metrics.operationsByTypeAndOutcome()
                .get("BROKER_REDRIVE:REQUESTED"));
        verify(requested.dao).countDlqBroad("tenant-a", "client-a", NOW);
    }

    @Test
    void internalCallsRejectMalformedPagesAndReadInsideRepeatableReadSnapshot() {
        Fixture fixture = fixture();
        AgentCommandOperationsServiceImpl service = service(fixture, null, null, false, false);
        assertEquals(AgentCommandOperationsException.Reason.INVALID_REQUEST,
                assertThrows(AgentCommandOperationsException.class,
                        () -> service.listDlq("tenant-\ud800", "client-a", 0, 1)).reason());

        when(fixture.dao.listDlqBroad("tenant-a", "client-a", 0, SERVICE_NOW, 101))
                .thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                    assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,
                            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                    return List.of(broadDelivery(2L), broadDelivery(1L));
                });
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                assertThrows(AgentCommandOperationsException.class,
                        () -> service.listDlq("tenant-a", "client-a", 0, 10)).reason());

        when(fixture.dao.listAudit("tenant-a", "client-a", 0, 2)).thenReturn(List.of(
                new cn.jia.agent.entity.AgentCommandOperationAuditEntry(
                        1L, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "REQUEST",
                        "BROKER_REDRIVE", "task-1", "agent-a", null, MESSAGE, null,
                        1L, null, null, null, "operator-a", null, "password=hunter2",
                        "INC-42", NOW, null, "REQUESTED", null, "operator-a", NOW)));
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                assertThrows(AgentCommandOperationsException.class,
                        () -> service.listAudit("tenant-a", "client-a", 0, 1)).reason());
    }

    private cn.jia.agent.entity.AgentCommandOperationAuditEntry auditEntry(long id) {
        return new cn.jia.agent.entity.AgentCommandOperationAuditEntry(
                id, id == 1 ? "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                        : "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "REQUEST", "BROKER_REDRIVE", "task-1", "agent-a", null,
                MESSAGE, null, 1L, null, null, null, "operator-a", null,
                "incident recovery", "INC-42", NOW, null, "REQUESTED", null,
                "operator-a", NOW);
    }

    private AgentCommandDeliveryEntity broadDelivery(long id) {
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(id).setStatus("PUBLISHED").setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setActiveMessageId("message-" + id);
        delivery.setTenantId("tenant-a");
        delivery.setClientId("client-a");
        return delivery;
    }

    private AgentCommandDlqEntry dlqEntry(long id) {
        return dlqEntry(id, "PUBLISHED", "PUBLISHED", null, null, null, EXPIRES);
    }

    private AgentCommandDlqEntry dlqEntry(
            long id,
            String deliveryStatus,
            String outboxStatus,
            String inboxStatus,
            String inboxResultStatus,
            Long processedAt,
            long expiresAt) {
        return new AgentCommandDlqEntry(
                id, COMMAND, "event-1", MESSAGE, "task-1", "agent-a",
                deliveryStatus, outboxStatus, inboxStatus, inboxResultStatus, 1, 1,
                "00".repeat(32), NOW - 10, processedAt, expiresAt, NOW);
    }

    private AgentCommandOperationsServiceImpl service(
            Fixture fixture, AgentCommandDlqRedriver redriver,
            AgentCommandReissueService reissue, boolean redriveEnabled, boolean reissueEnabled) {
        return new AgentCommandOperationsServiceImpl(
                fixture.dao, enabledGate(), AgentRabbitTopologyManifest.canonical(), redriver,
                reissue, new AgentCommandOperationsProperties(
                        true, redriveEnabled, reissueEnabled, null, null, null, null),
                transactionManager(), () -> UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), () -> SERVICE_NOW);
    }

    private Fixture fixture() {
        AgentCommandOperationsDao dao = mock(AgentCommandOperationsDao.class);
        AgentCommandDraft draft = new AgentCommandDraft(
                1, COMMAND, "task-1", INTENT,
                "tenant-a", "client-a", "task-1", "work-1", "agent-a",
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE, ISSUED, EXPIRES,
                INTENT, new AgentHallCommandPayload(
                        "execute", "Execute the bounded Hall work item", "juyiting",
                        null, null, null, null, false, null));
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, MESSAGE, 1);
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(1L).setCommandId(COMMAND).setTaskId("task-1").setWorkItemId("work-1")
                .setTargetAgentId("agent-a")
                .setCommandType(AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE)
                .setCommandPayload(business).setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveMessageId(MESSAGE)
                .setActiveAttempt(1).setExpiresAt(EXPIRES).setVersion(1L);
        delivery.setTenantId("tenant-a"); delivery.setClientId("client-a");
        delivery.setCreateTime(NOW - 20); delivery.setUpdateTime(NOW - 5);
        var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setId(2L).setEventId("event-1").setMessageId(MESSAGE).setCommandId(COMMAND)
                .setDeliveryId(1L).setAggregateType("task").setAggregateId("task-1")
                .setDestination(route.destination()).setRoutingKey(route.routingKey())
                .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setPublisherConfirmStatus("ACK").setConfirmedAt(NOW - 10)
                .setMandatoryReturnStatus("NOT_RETURNED").setPublishedAt(NOW - 9).setVersion(1L);
        outbox.setTenantId("tenant-a"); outbox.setClientId("client-a");
        outbox.setCreateTime(NOW - 20); outbox.setUpdateTime(NOW - 4);
        when(dao.lockDelivery("tenant-a", "client-a", 1L)).thenReturn(delivery);
        when(dao.lockActiveOutboxes("tenant-a", "client-a", 1L, MESSAGE))
                .thenReturn(List.of(outbox));
        when(dao.lockCurrentAttemptOutboxes("tenant-a", "client-a", 1L, 1))
                .thenReturn(List.of(outbox));
        when(dao.lockPreviousAttemptOutboxes("tenant-a", "client-a", 1L, 0))
                .thenReturn(List.of());
        when(dao.lockActiveRedriveOperations("tenant-a", "client-a", 1L, MESSAGE, 1))
                .thenReturn(List.of());
        when(dao.selectActiveOutboxes("tenant-a", "client-a", 1L, MESSAGE))
                .thenReturn(List.of(outbox));
        when(dao.selectCurrentAttemptOutboxes("tenant-a", "client-a", 1L, 1))
                .thenReturn(List.of(outbox));
        when(dao.selectPreviousAttemptOutboxes("tenant-a", "client-a", 1L, 0))
                .thenReturn(List.of());
        when(dao.selectInbox("tenant-a", "client-a", "agent-command-dispatch-v1", MESSAGE))
                .thenReturn(null);
        when(dao.selectActiveRedriveOperations("tenant-a", "client-a", 1L, MESSAGE, 1))
                .thenReturn(List.of());
        when(dao.lockInbox("tenant-a", "client-a", "agent-command-dispatch-v1", MESSAGE))
                .thenReturn(null);
        when(dao.oldestOutboxEpoch("tenant-a", "client-a")).thenReturn(null);
        AtomicLong ids = new AtomicLong(10);
        doAnswer(invocation -> {
            ((AgentCommandOperationAuditEntity) invocation.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(dao).insertAudit(any());
        return new Fixture(dao, delivery, outbox, ids);
    }

    private AgentCommandOperationRequest request(String approver) {
        return new AgentCommandOperationRequest("tenant-a", "client-a", 1L, "task-1",
                "agent-a", MESSAGE, "operator-a", approver, "incident recovery", "INC-42");
    }

    private AgentRabbitSafetyGate enabledGate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker("isolated.invalid", 35672,
                        "user", "secret", "/isolated")),
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                "tenant-a", "client-a"))));
    }

    private DataSourceTransactionManager transactionManager() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:d09_ops_" + System.nanoTime() + ";MODE=MYSQL");
        return new DataSourceTransactionManager(dataSource);
    }

    private record Fixture(AgentCommandOperationsDao dao, AgentCommandDeliveryEntity delivery,
                           AgentOutboxEventEntity outbox, AtomicLong ids) {}
}
