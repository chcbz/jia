package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentInboxResult;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandInboxV2StateMachineTest {
    private static final long NOW = 1_000L;
    private static final long EXPIRES = 10_000L;
    private static final byte[] WIRE = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "local-test", "consumer-a",
            3, 4, 7, "agent-command", 9);

    @Test
    void firstClaimPersistsInboxAndConsumesDeliveryWithFencedToken() {
        Fixture fixture = new Fixture();
        fixture.source("PUBLISHED", 5);
        when(fixture.dao.lockInbox(any())).thenReturn(null);
        when(fixture.dao.insert(any())).thenAnswer(invocation -> {
            AgentConsumerInboxEntity inbox = invocation.getArgument(0);
            inbox.setId(99L);
            return 1;
        });
        when(fixture.dao.transitionDelivery(any(), eq(1), eq(5L),
                eq("PUBLISHED"), eq("CONSUMED"), eq(null), eq(null), eq(NOW))).thenReturn(1);

        AgentInboxClaim claim = fixture.service.claim(message(), "lease-a", NOW, 2_000);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        AgentInboxClaimToken token = claim.token();
        assertNotNull(token);
        assertEquals(3_000L, token.leaseUntil());
        assertEquals(0L, token.inboxVersion());
        assertEquals(6L, token.deliveryVersion());
        assertEquals("task-1", token.taskId());
        assertEquals("agent-1", token.targetAgentId());
        assertEquals("TASK_INVITE", token.commandType());
        verify(fixture.dao).transitionDelivery(any(), eq(1), eq(5L),
                eq("PUBLISHED"), eq("CONSUMED"), eq(null), eq(null), eq(NOW));
    }

    @Test
    void activeProcessingDuplicateIsInFlightWithoutMutation() {
        Fixture fixture = new Fixture();
        fixture.source("CONSUMED", 6);
        when(fixture.dao.lockInbox(any())).thenReturn(fixture.processing(2_000L));

        AgentInboxClaim claim = fixture.service.claim(message(), "lease-b", NOW, 500);

        assertEquals(AgentInboxClaim.Kind.IN_FLIGHT, claim.kind());
        assertEquals(1_000L, claim.retryAfterMillis());
        verify(fixture.dao, never()).reclaimProcessingInbox(any(), any(), any(), any(Long.class), any(Long.class));
        verify(fixture.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void expiredProcessingLeaseIsReclaimedWithoutChangingDeliveryFence() {
        Fixture fixture = new Fixture();
        fixture.source("CONSUMED", 6);
        AgentConsumerInboxEntity inbox = fixture.processing(900L);
        when(fixture.dao.lockInbox(any())).thenReturn(inbox);
        when(fixture.dao.reclaimProcessingInbox(any(), eq(inbox), eq("lease-b"), eq(1_500L), eq(NOW)))
                .thenReturn(1);

        AgentInboxClaim claim = fixture.service.claim(message(), "lease-b", NOW, 500);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        assertEquals(3, claim.token().activeAttempt());
        assertEquals(5L, claim.token().inboxVersion());
        assertEquals(6L, claim.token().deliveryVersion());
        verify(fixture.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void retryBeforeDueIsInFlightAndDueRetryIsAtomicallyReclaimed() {
        Fixture waiting = new Fixture();
        waiting.source("RETRY", 6);
        when(waiting.dao.lockInbox(any())).thenReturn(waiting.retry(2_000L));
        AgentInboxClaim inFlight = waiting.service.claim(message(), "lease-b", NOW, 500);
        assertEquals(AgentInboxClaim.Kind.IN_FLIGHT, inFlight.kind());
        assertEquals(1_000L, inFlight.retryAfterMillis());

        Fixture due = new Fixture();
        due.source("RETRY", 6);
        AgentConsumerInboxEntity inbox = due.retry(900L);
        when(due.dao.lockInbox(any())).thenReturn(inbox);
        when(due.dao.transitionDelivery(any(), eq(1), eq(6L),
                eq("RETRY"), eq("CONSUMED"), eq(null), eq(null), eq(NOW))).thenReturn(1);
        when(due.dao.reclaimRetryInbox(any(), eq(inbox), eq("lease-b"), eq(1_500L), eq(7L), eq(NOW)))
                .thenReturn(1);

        AgentInboxClaim acquired = due.service.claim(message(), "lease-b", NOW, 500);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, acquired.kind());
        assertEquals(7L, acquired.token().deliveryVersion());
        assertEquals(5L, acquired.token().inboxVersion());
    }

    @Test
    void firstAndExistingClaimsExpireDurablyAtAuthoritativeBoundary() {
        Fixture first = new Fixture();
        first.source("PUBLISHED", 5, EXPIRES);
        when(first.dao.lockInbox(any())).thenReturn(null);
        when(first.dao.insert(any())).thenAnswer(invocation -> {
            ((AgentConsumerInboxEntity) invocation.getArgument(0)).setId(99L);
            return 1;
        });
        when(first.dao.transitionDelivery(any(), eq(1), eq(5L),
                eq("PUBLISHED"), eq("EXPIRED"), eq(null),
                eq(AgentCommandInboxV2ServiceImpl.MESSAGE_EXPIRED), eq(EXPIRES))).thenReturn(1);
        AgentInboxClaim firstExpired = first.service.claim(message(), "lease-a", EXPIRES, 500);
        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, firstExpired.kind());
        assertEquals("EXPIRED", firstExpired.priorResult().status());

        Fixture processing = new Fixture();
        processing.source("CONSUMED", 6, EXPIRES);
        AgentConsumerInboxEntity inbox = processing.processing(9_000L);
        when(processing.dao.lockInbox(any())).thenReturn(inbox);
        when(processing.dao.transitionDelivery(any(), eq(1), eq(6L),
                eq("CONSUMED"), eq("EXPIRED"), eq(null),
                eq(AgentCommandInboxV2ServiceImpl.MESSAGE_EXPIRED), eq(EXPIRES))).thenReturn(1);
        when(processing.dao.expireProcessingInbox(any(), eq(inbox),
                eq(AgentCommandInboxV2ServiceImpl.MESSAGE_EXPIRED), eq(EXPIRES))).thenReturn(1);
        AgentInboxClaim processingExpired = processing.service.claim(
                message(), "lease-b", EXPIRES, 500);
        assertEquals("EXPIRED", processingExpired.priorResult().status());
    }

    @Test
    void executionAndSettlementRecheckDurableTokenAndLeaseBoundary() {
        Fixture fixture = new Fixture();
        fixture.source("CONSUMED", 6);
        AgentConsumerInboxEntity inbox = fixture.processing(2_000L);
        when(fixture.dao.lockInbox(any())).thenReturn(inbox);
        AgentInboxClaimToken token = fixture.token(inbox, 6L);

        execute(fixture.service, token, 1_500L);
        assertThrows(IllegalStateException.class,
                () -> execute(fixture.service, token, 2_000L));

        when(fixture.dao.settleDeliveryFromClaim(eq(token), eq(inbox),
                eq("CONSUMED"), eq("SENT"), eq(null), eq(null), eq(1_500L))).thenReturn(1);
        when(fixture.dao.settleInbox(eq(token), eq("PROCESSED"), eq("SENT"),
                eq(null), eq(null), eq(1_500L))).thenReturn(1);
        AgentInboxResult result = fixture.service.complete(
                token, AgentInboxDisposition.sent(), 1_500L);
        assertEquals("PROCESSED", result.status());
        assertEquals("SENT", result.resultStatus());

        assertThrows(IllegalStateException.class,
                () -> fixture.service.complete(token, AgentInboxDisposition.sent(), 2_000L));
    }

    @Test
    void adjacentDurableScopeFailsClosedBeforeAnyMutation() {
        Fixture fixture = new Fixture();
        fixture.source("PUBLISHED", 5);
        fixture.delivery.setDataDomainId("other-domain");

        assertThrows(IllegalStateException.class,
                () -> fixture.service.claim(message(), "lease-a", NOW, 500));

        verify(fixture.dao, never()).insert(any());
        verify(fixture.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                any(), any(), any(), any(), any(Long.class));
    }


    @Test
    void everyBusinessIdentityMismatchFailsUnderTheSourceLockBeforeMutation() {
        for (String field : new String[]{"task", "target", "type"}) {
            Fixture fixture = new Fixture();
            fixture.source("PUBLISHED", 5);
            if (field.equals("task")) fixture.delivery.setTaskId("other-task");
            if (field.equals("target")) fixture.delivery.setTargetAgentId("other-agent");
            if (field.equals("type")) fixture.delivery.setCommandType("WORK_ITEM_EXECUTE");

            assertThrows(IllegalStateException.class,
                    () -> fixture.service.claim(message(), "lease-a", NOW, 500), field);

            verify(fixture.dao, never()).lockInbox(any());
            verify(fixture.dao, never()).insert(any());
            verify(fixture.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                    any(), any(), any(), any(), any(Long.class));
        }
    }

    @Test
    void tamperedBusinessIdentityTokenCannotVerifyOrComplete() {
        for (String field : new String[]{"task", "target", "type"}) {
            Fixture fixture = processingFixture();
            AgentConsumerInboxEntity inbox = fixture.processing(2_000L);
            when(fixture.dao.lockInbox(any())).thenReturn(inbox);
            AgentInboxClaimToken token = fixture.alteredToken(fixture.token(inbox, 6L), field);

            assertThrows(IllegalStateException.class,
                    () -> execute(fixture.service, token, 1_500L), field);
            assertThrows(IllegalStateException.class,
                    () -> fixture.service.complete(token, AgentInboxDisposition.sent(), 1_500L), field);

            verify(fixture.dao, never()).settleDeliveryFromClaim(
                    any(), any(), any(), any(), any(), any(), any(Long.class));
            verify(fixture.dao, never()).settleInbox(
                    any(), any(), any(), any(), any(), any(Long.class));
        }
    }
    @Test
    void retryClaimExpiresDurablyAtAuthoritativeBoundary() {
        Fixture fixture = new Fixture();
        fixture.source("RETRY", 6, EXPIRES);
        AgentConsumerInboxEntity inbox = fixture.retry(900L);
        when(fixture.dao.lockInbox(any())).thenReturn(inbox);
        when(fixture.dao.transitionDelivery(any(), eq(1), eq(6L),
                eq("RETRY"), eq("EXPIRED"), eq(null),
                eq(AgentCommandInboxV2ServiceImpl.MESSAGE_EXPIRED), eq(EXPIRES))).thenReturn(1);
        when(fixture.dao.expireRetryInbox(any(), eq(inbox),
                eq(AgentCommandInboxV2ServiceImpl.MESSAGE_EXPIRED), eq(EXPIRES))).thenReturn(1);

        AgentInboxClaim expired = fixture.service.claim(message(), "lease-b", EXPIRES, 500);

        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, expired.kind());
        assertEquals("EXPIRED", expired.priorResult().status());
    }

    @Test
    void everyFailedCasFailsClosedAndRollsBack() {
        Fixture first = new Fixture();
        first.source("PUBLISHED", 5);
        when(first.dao.lockInbox(any())).thenReturn(null);
        when(first.dao.insert(any())).thenAnswer(invocation -> {
            ((AgentConsumerInboxEntity) invocation.getArgument(0)).setId(99L);
            return 1;
        });
        assertThrows(IllegalStateException.class,
                () -> first.service.claim(message(), "lease-a", NOW, 500));
        verify(first.manager).rollback(first.transaction);

        Fixture deliveryCas = processingFixture();
        AgentInboxClaimToken deliveryToken = deliveryCas.token(
                (AgentConsumerInboxEntity) deliveryCas.dao.lockInbox(message()), 6L);
        assertThrows(IllegalStateException.class,
                () -> deliveryCas.service.complete(
                        deliveryToken, AgentInboxDisposition.sent(), 1_500L));
        verify(deliveryCas.dao, never()).settleInbox(any(), any(), any(), any(), any(), any(Long.class));
        verify(deliveryCas.manager).rollback(deliveryCas.transaction);

        Fixture inboxCas = processingFixture();
        AgentConsumerInboxEntity inbox = inboxCas.processing(2_000L);
        when(inboxCas.dao.lockInbox(any())).thenReturn(inbox);
        AgentInboxClaimToken inboxToken = inboxCas.token(inbox, 6L);
        when(inboxCas.dao.settleDeliveryFromClaim(eq(inboxToken), eq(inbox),
                eq("CONSUMED"), eq("SENT"), eq(null), eq(null), eq(1_500L))).thenReturn(1);
        assertThrows(IllegalStateException.class,
                () -> inboxCas.service.complete(inboxToken, AgentInboxDisposition.sent(), 1_500L));
        verify(inboxCas.manager).rollback(inboxCas.transaction);
    }

    @Test
    void retryAndExpiredDispositionBoundariesAreExact() {
        Fixture retry = processingFixture();
        AgentConsumerInboxEntity retryInbox = retry.processing(2_000L);
        when(retry.dao.lockInbox(any())).thenReturn(retryInbox);
        AgentInboxClaimToken retryToken = retry.token(retryInbox, 6L);
        assertThrows(IllegalStateException.class, () -> retry.service.complete(retryToken,
                new AgentInboxDisposition(AgentInboxDisposition.Type.RETRY, 1_500L, "TRANSIENT"),
                1_500L));
        assertThrows(IllegalStateException.class, () -> retry.service.complete(retryToken,
                new AgentInboxDisposition(AgentInboxDisposition.Type.RETRY, EXPIRES, "TRANSIENT"),
                1_500L));
        verify(retry.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                any(), any(), any(), any(), any(Long.class));

        AgentInboxDisposition expired = new AgentInboxDisposition(
                AgentInboxDisposition.Type.EXPIRED, null, "MESSAGE_EXPIRED");
        Fixture early = processingFixture();
        AgentConsumerInboxEntity earlyInbox = early.processing(EXPIRES);
        when(early.dao.lockInbox(any())).thenReturn(earlyInbox);
        AgentInboxClaimToken earlyToken = early.token(earlyInbox, 6L);
        assertThrows(IllegalStateException.class,
                () -> early.service.complete(earlyToken, expired, EXPIRES - 1));

        Fixture shortLease = processingFixture();
        AgentConsumerInboxEntity shortInbox = shortLease.processing(2_000L);
        when(shortLease.dao.lockInbox(any())).thenReturn(shortInbox);
        AgentInboxClaimToken shortToken = shortLease.token(shortInbox, 6L);
        assertThrows(IllegalStateException.class,
                () -> shortLease.service.complete(shortToken, expired, EXPIRES));

        Fixture exact = processingFixture();
        AgentConsumerInboxEntity exactInbox = exact.processing(EXPIRES);
        when(exact.dao.lockInbox(any())).thenReturn(exactInbox);
        AgentInboxClaimToken exactToken = exact.token(exactInbox, 6L);
        when(exact.dao.settleDeliveryFromClaim(eq(exactToken), eq(exactInbox),
                eq("CONSUMED"), eq("EXPIRED"), eq(null),
                eq("MESSAGE_EXPIRED"), eq(EXPIRES))).thenReturn(1);
        when(exact.dao.settleInbox(eq(exactToken), eq("EXPIRED"), eq("EXPIRED"),
                eq(null), eq("MESSAGE_EXPIRED"), eq(EXPIRES))).thenReturn(1);
        assertEquals("EXPIRED", exact.service.complete(exactToken, expired, EXPIRES).status());
    }

    @Test
    void executionRecheckRejectsDurableVersionOwnerAttemptAndDeliveryDrift() {
        assertExecutionFenceRejected(inbox -> inbox.setVersion(inbox.getVersion() + 1));
        assertExecutionFenceRejected(inbox -> inbox.setLeaseOwner("other-owner"));
        assertExecutionFenceRejected(inbox -> inbox.setLeaseUntil(inbox.getLeaseUntil() + 1));
        assertExecutionFenceRejected(inbox -> inbox.setActiveAttempt(inbox.getActiveAttempt() + 1));

        Fixture deliveryVersion = processingFixture();
        AgentConsumerInboxEntity inbox = deliveryVersion.processing(2_000L);
        when(deliveryVersion.dao.lockInbox(any())).thenReturn(inbox);
        AgentInboxClaimToken token = deliveryVersion.token(inbox, 6L);
        deliveryVersion.delivery.setVersion(7L);
        assertThrows(IllegalStateException.class,
                () -> execute(deliveryVersion.service, token, 1_500L));
        verify(deliveryVersion.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void terminalDuplicateRequiresExactStoredResult() {
        Fixture valid = new Fixture();
        valid.source("SENT", 7);
        AgentConsumerInboxEntity processed = valid.inbox(
                "PROCESSED", "SENT", null, null, null, 1_500L, null);
        when(valid.dao.lockInbox(any())).thenReturn(processed);
        assertEquals("SENT", valid.service.claim(message(), "lease-b", 1_600L, 500)
                .priorResult().resultStatus());

        Fixture forged = new Fixture();
        forged.source("SENT", 7);
        AgentConsumerInboxEntity bad = forged.inbox(
                "PROCESSED", "FAILED", null, null, null, 1_500L, "FORGED");
        when(forged.dao.lockInbox(any())).thenReturn(bad);
        assertThrows(IllegalStateException.class,
                () -> forged.service.claim(message(), "lease-b", 1_600L, 500));
    }

    @Test
    void corruptAttemptAndCrossRowDispositionStateFailBeforeMutation() {
        Fixture attempt = new Fixture();
        attempt.source("CONSUMED", 6);
        AgentConsumerInboxEntity attemptInbox = attempt.processing(2_000L).setAttemptCount(3);
        when(attempt.dao.lockInbox(any())).thenReturn(attemptInbox);
        assertThrows(IllegalStateException.class,
                () -> attempt.service.claim(message(), "lease-b", NOW, 500));
        verify(attempt.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                any(), any(), any(), any(), any(Long.class));

        Fixture processing = new Fixture();
        processing.source("CONSUMED", 6);
        processing.delivery.setNextRetryAt(1_500L);
        when(processing.dao.lockInbox(any())).thenReturn(processing.processing(2_000L));
        assertThrows(IllegalStateException.class,
                () -> processing.service.claim(message(), "lease-b", NOW, 500));
        verify(processing.dao, never()).reclaimProcessingInbox(
                any(), any(), any(), any(Long.class), any(Long.class));

        Fixture retry = new Fixture();
        retry.source("RETRY", 6);
        AgentConsumerInboxEntity retryInbox = retry.retry(2_000L);
        retry.delivery.setNextRetryAt(2_001L);
        when(retry.dao.lockInbox(any())).thenReturn(retryInbox);
        assertThrows(IllegalStateException.class,
                () -> retry.service.claim(message(), "lease-b", NOW, 500));
        verify(retry.dao, never()).reclaimRetryInbox(
                any(), any(), any(), any(Long.class), any(Long.class), any(Long.class));

        Fixture terminal = new Fixture();
        terminal.source("WAITING_AGENT", 7);
        terminal.delivery.setNextRetryAt(2_000L).setLastError("AGENT_OFFLINE");
        AgentConsumerInboxEntity waiting = terminal.inbox(
                "WAITING_AGENT", "WAITING_AGENT", 2_001L,
                null, null, 1_500L, "AGENT_OFFLINE");
        when(terminal.dao.lockInbox(any())).thenReturn(waiting);
        assertThrows(IllegalStateException.class,
                () -> terminal.service.claim(message(), "lease-b", 1_600L, 500));
    }

    private static Fixture processingFixture() {
        Fixture fixture = new Fixture();
        fixture.source("CONSUMED", 6);
        AgentConsumerInboxEntity inbox = fixture.processing(2_000L);
        when(fixture.dao.lockInbox(any())).thenReturn(inbox);
        return fixture;
    }

    private static void assertExecutionFenceRejected(Consumer<AgentConsumerInboxEntity> drift) {
        Fixture fixture = processingFixture();
        AgentConsumerInboxEntity inbox = fixture.processing(2_000L);
        when(fixture.dao.lockInbox(any())).thenReturn(inbox);
        AgentInboxClaimToken token = fixture.token(inbox, 6L);
        drift.accept(inbox);

        assertThrows(IllegalStateException.class,
                () -> execute(fixture.service, token, 1_500L));
        verify(fixture.dao, never()).transitionDelivery(any(), any(Integer.class), any(Long.class),
                any(), any(), any(), any(), any(Long.class));
        verify(fixture.dao, never()).settleInbox(any(), any(), any(), any(), any(), any(Long.class));
    }

    private static AgentInboxMessage message() {
        return new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                "1", "cyf", "local-test", 7, "producer-a", "agent-command", 9, 2,
                "consumer-a", 3, "client-a", "msg-1", "evt-1", "cmd-1",
                "task-1", "agent-1", "TASK_INVITE", 41, WIRE);
    }

    private static AgentCommandV2ExecutionPort.Result execute(
            AgentCommandInboxV2ServiceImpl service, AgentInboxClaimToken token, long now) {
        return service.executeAdmitted(token, "owner-a", WIRE, now,
                command -> AgentCommandV2ExecutionPort.Result.dispatched());
    }

    private static final class Fixture {
        final AgentCommandInboxV2Dao dao = mock(AgentCommandInboxV2Dao.class);
        final TenantModuleAdmissionGuard guard = mock(TenantModuleAdmissionGuard.class);
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus transaction = mock(TransactionStatus.class);
        final AgentCommandInboxV2ServiceImpl service;
        AgentCommandDeliveryEntity delivery;
        AgentOutboxEventEntity outbox;

        @SuppressWarnings("unchecked")
        Fixture() {
            when(manager.getTransaction(any())).thenReturn(transaction);
            doAnswer(invocation -> {
                Function<TenantModuleAdmissionGuard.Mode, Object> work = invocation.getArgument(2);
                return work.apply(TenantModuleAdmissionGuard.Mode.DOMAIN);
            }).when(guard).withWorkerAdmission(eq(SCOPE), any(), any());
            service = new AgentCommandInboxV2ServiceImpl(dao, SCOPE, guard, manager);
        }

        void source(String status, long version) {
            source(status, version, EXPIRES);
        }

        void source(String status, long version, long expiresAt) {
            byte[] business = new byte[]{7};
            delivery = new AgentCommandDeliveryEntity()
                    .setId(41L).setProtocolVersion(2).setDataDomainId("cyf")
                    .setExecutionPoolId("local-test").setAdmissionEpoch(7L)
                    .setProducerDeploymentId("producer-a").setModuleId("agent-command")
                    .setMigrationEpoch(9L).setClaimedDeploymentId(null)
                    .setClaimedWorkerGeneration(null).setCommandId("cmd-1")
                    .setTaskId("task-1").setTargetAgentId("agent-1").setCommandType("TASK_INVITE")
                    .setCommandPayload(business).setCommandPayloadHash(AgentCommandAmqpContract.sha256(business))
                    .setStatus(status).setAttemptCount(1).setActiveMessageId("msg-1")
                    .setActiveAttempt(1).setExpiresAt(expiresAt).setVersion(version);
            delivery.setTenantId("1");
            delivery.setClientId("client-a");
            outbox = new AgentOutboxEventEntity()
                    .setId(51L).setProtocolVersion(2).setDataDomainId("cyf")
                    .setExecutionPoolId("local-test").setAdmissionEpoch(7L)
                    .setProducerDeploymentId("producer-a").setModuleId("agent-command")
                    .setMigrationEpoch(9L).setClaimedDeploymentId(null)
                    .setClaimedWorkerGeneration(null).setEventId("evt-1").setMessageId("msg-1")
                    .setCommandId("cmd-1").setDeliveryId(41L).setWirePayload(WIRE)
                    .setWirePayloadHash(AgentCommandAmqpContract.sha256(WIRE)).setStatus("PUBLISHED")
                    .setAttemptCount(1).setActiveAttempt(1).setExpiresAt(expiresAt).setVersion(7L);
            outbox.setTenantId("1");
            outbox.setClientId("client-a");
            when(dao.lockDelivery(any())).thenReturn(delivery);
            when(dao.lockOutbox(any())).thenReturn(outbox);
            when(dao.lockPrevious(any(), eq(1))).thenReturn(List.of());
        }

        AgentConsumerInboxEntity processing(long leaseUntil) {
            return inbox("PROCESSING", null, null, "lease-a", leaseUntil, null, null);
        }

        AgentConsumerInboxEntity retry(long nextRetryAt) {
            delivery.setNextRetryAt(nextRetryAt).setLastError("TRANSIENT");
            return inbox("RETRY", "RETRY", nextRetryAt, null, null, 800L, "TRANSIENT");
        }

        AgentConsumerInboxEntity inbox(String status, String result, Long nextRetryAt,
                String leaseOwner, Long leaseUntil, Long processedAt, String error) {
            AgentConsumerInboxEntity inbox = new AgentConsumerInboxEntity()
                    .setId(99L).setProtocolVersion(2).setDataDomainId("cyf")
                    .setExecutionPoolId("local-test").setAdmissionEpoch(7L)
                    .setProducerDeploymentId("producer-a").setModuleId("agent-command")
                    .setMigrationEpoch(9L).setClaimantDeploymentId("consumer-a")
                    .setClaimantWorkerGeneration(3L).setConsumerName(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2)
                    .setMessageId("msg-1").setEventId("evt-1").setCommandId("cmd-1")
                    .setDeliveryId(41L).setWirePayload(WIRE)
                    .setWirePayloadHash(AgentCommandAmqpContract.sha256(WIRE))
                    .setStatus(status).setResultStatus(result).setAttemptCount(2)
                    .setNextRetryAt(nextRetryAt).setLeaseOwner(leaseOwner).setLeaseUntil(leaseUntil)
                    .setActiveAttempt(2).setExpiresAt(EXPIRES).setProcessedAt(processedAt)
                    .setLastError(error).setVersion(4L)
                    .setClaimDeliveryVersion(delivery.getVersion()).setClaimInboxVersion(4L)
                    .setClaimStartedAt(700L);
            inbox.setTenantId("1");
            inbox.setClientId("client-a");
            return inbox;
        }

        AgentInboxClaimToken token(AgentConsumerInboxEntity inbox, long deliveryVersion) {
            return new AgentInboxClaimToken(inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(),
                    inbox.getDataDomainId(), inbox.getExecutionPoolId(), inbox.getAdmissionEpoch(),
                    inbox.getProducerDeploymentId(), inbox.getModuleId(), inbox.getMigrationEpoch(), 2,
                    inbox.getClaimantDeploymentId(), inbox.getClaimantWorkerGeneration(), inbox.getClientId(),
                    inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(),
                    delivery.getTaskId(), delivery.getTargetAgentId(), delivery.getCommandType(), inbox.getDeliveryId(),
                    inbox.getLeaseOwner(), inbox.getLeaseUntil(), inbox.getActiveAttempt(), inbox.getVersion(),
                    delivery.getActiveAttempt(), deliveryVersion, inbox.getExpiresAt());
        }

        AgentInboxClaimToken alteredToken(AgentInboxClaimToken token, String field) {
            return new AgentInboxClaimToken(token.inboxId(), token.consumerName(),
                    token.tenantId(), token.dataDomainId(), token.executionPoolId(),
                    token.admissionEpoch(), token.producerDeploymentId(), token.moduleId(),
                    token.migrationEpoch(), token.protocolVersion(),
                    token.claimantDeploymentId(), token.claimantWorkerGeneration(),
                    token.clientId(), token.messageId(), token.eventId(),
                    token.commandId(),
                    field.equals("task") ? "other-task" : token.taskId(),
                    field.equals("target") ? "other-agent" : token.targetAgentId(),
                    field.equals("type") ? "WORK_ITEM_EXECUTE" : token.commandType(),
                    token.deliveryId(), token.leaseOwner(), token.leaseUntil(),
                    token.activeAttempt(), token.inboxVersion(), token.deliveryActiveAttempt(),
                    token.deliveryVersion(), token.expiresAt());
        }
    }
}
