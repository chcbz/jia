package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.dao.AgentCommandInboxDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxFenceException;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentInboxSourceNotSettledException;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandInboxServiceImplTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long LEASE = 10_000L;
    private static final long EXPIRES_AT = NOW + 60_000L;

    @Test
    void offDbShadowAndConsumeDisabledReturnBeforeAnyDaoAccess() {
        for (AgentRabbitSafetyGate gate : List.of(offGate(), dbShadowGate(), consumeDisabledGate())) {
            RecordingDao dao = new RecordingDao();
            AgentCommandInboxServiceImpl service = service(dao, gate);

            AgentInboxClaim claim = service.claim(message(), "worker-a", NOW, LEASE);

            assertEquals(AgentInboxClaim.Kind.DISABLED, claim.kind());
            assertEquals(0, dao.accesses);
        }
    }

    @Test
    void confirmedPublishVisibleBeforeD03SettlementIsTypedTransientAfterProvenanceValidation() {
        RecordingDao outboxRace = new RecordingDao();
        canonicalClaimed(outboxRace, "PENDING");
        AgentInboxSourceNotSettledException outboxFailure = assertThrows(
                AgentInboxSourceNotSettledException.class,
                () -> service(outboxRace, enabledGate())
                        .claim(message(), "worker-a", NOW, LEASE));
        assertEquals("OUTBOX_NOT_PUBLISHED", outboxFailure.reasonCode());
        assertEquals(List.of("delivery", "outbox", "inbox"), outboxRace.operations);
        assertEquals(null, outboxRace.inbox);

        RecordingDao retryRace = new RecordingDao();
        canonicalClaimed(retryRace, "RETRY");
        assertThrows(AgentInboxSourceNotSettledException.class,
                () -> service(retryRace, enabledGate())
                        .claim(message(), "worker-a", NOW, LEASE));

        RecordingDao impossibleDeliveryClaim = new RecordingDao();
        impossibleDeliveryClaim.delivery.setStatus("CLAIMED");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(impossibleDeliveryClaim, enabledGate())
                        .claim(message(), "worker-a", NOW, LEASE));
        assertEquals(null, impossibleDeliveryClaim.inbox);

        for (String deterministic : List.of("PENDING", "RETRY", "FAILED", "DEAD")) {
            RecordingDao corrupt = new RecordingDao();
            corrupt.outbox.setStatus(deterministic);
            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(corrupt, enabledGate())
                            .claim(message(), "worker-a", NOW, LEASE), deterministic);
            assertEquals(null, corrupt.inbox, deterministic);
        }
    }

    @Test
    void claimedTransientAcceptsCanonicalStaleLeaseAndReservedSettlementVersionEdges() {
        List<java.util.function.Consumer<RecordingDao>> canonicalEdges = List.of(
                dao -> {
                    dao.delivery.setLeaseUntil(NOW - 1);
                    dao.outbox.setLeaseUntil(NOW - 1);
                },
                dao -> dao.delivery.setVersion(Long.MAX_VALUE - 1),
                dao -> dao.outbox.setVersion(Long.MAX_VALUE - 1));
        for (var edge : canonicalEdges) {
            RecordingDao dao = new RecordingDao();
            canonicalClaimed(dao, "PENDING");
            edge.accept(dao);

            assertThrows(AgentInboxSourceNotSettledException.class,
                    () -> service(dao, enabledGate())
                            .claim(message(), "worker-a", NOW, LEASE));
            assertEquals(null, dao.inbox);
        }
    }

    @Test
    void claimedSourceCannotHideCorruptExistingInboxOrActiveFenceDrift() {
        RecordingDao corruptInbox = new RecordingDao();
        AgentCommandInboxServiceImpl corruptService = service(corruptInbox, enabledGate());
        corruptService.claim(message(), "worker-a", NOW, LEASE);
        corruptInbox.outbox.setStatus("CLAIMED");
        corruptInbox.inbox.setWirePayloadHash(new byte[32]);

        assertThrows(AgentInboxIdentityConflictException.class,
                () -> corruptService.claim(message(), "worker-b", NOW + 1, LEASE));
        assertTrue(corruptInbox.operations.contains("inbox"));
        assertEquals("PROCESSING", corruptInbox.inbox.getStatus());

        RecordingDao existingInbox = new RecordingDao();
        AgentCommandInboxServiceImpl existingService = service(existingInbox, enabledGate());
        existingService.claim(message(), "worker-a", NOW, LEASE);
        existingInbox.outbox.setStatus("CLAIMED");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> existingService.claim(message(), "worker-b", NOW + 1, LEASE));

        RecordingDao activeFence = new RecordingDao();
        canonicalClaimed(activeFence, "PENDING");
        activeFence.delivery.setActiveMessageId("msg-other");

        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(activeFence, enabledGate())
                        .claim(message(), "worker-a", NOW, LEASE));
        assertEquals(null, activeFence.inbox);
    }

    @Test
    void sourceRaceDoesNotOverrideWireIdentityOrStoredHashConflicts() {
        RecordingDao identity = new RecordingDao();
        canonicalClaimed(identity, "PENDING");
        AgentInboxMessage wrongEvent = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-other", "cmd-1", 1,
                message().rawWireBytes());
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(identity, enabledGate())
                        .claim(wrongEvent, "worker-a", NOW, LEASE));

        RecordingDao hash = new RecordingDao();
        canonicalClaimed(hash, "PENDING");
        hash.outbox.setWirePayloadHash(new byte[32]);
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(hash, enabledGate())
                        .claim(message(), "worker-a", NOW, LEASE));
    }

    @Test
    void frozenWireProvenanceMustRemainStrictBeforeAnySourceLock() {
        String canonical = new String(
                message().rawWireBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<byte[]> malformed = List.of(
                canonical.replace("\"taskId\":\"task-1\"",
                                "\"taskId\":\"task-1\",\"taskId\":\"task-2\"")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (canonical + " {}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                canonical.replace("\"payload\":{}",
                                "\"eventId\":\"evt-1\",\"payload\":{}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                canonical.replace("\"payload\":{}",
                                "\"payload\":{\"taskId\":\"task-other\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                canonical.replace("\"commandType\":\"TASK_INVITE\"",
                                "\"commandType\":\"UNSUPPORTED\"")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[] {(byte) 0xc3, (byte) 0x28});
        for (byte[] raw : malformed) {
            RecordingDao dao = new RecordingDao();
            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(dao, enabledGate())
                            .claim(message(raw), "worker-a", NOW, LEASE));
            assertEquals(0, dao.accesses);
        }
    }

    @Test
    void claimedTransientRejectsOutboxFrozenProvenanceDrift() {
        List<java.util.function.Consumer<RecordingDao>> corruptions = List.of(
                dao -> dao.outbox.setEventId("evt-other"),
                dao -> dao.outbox.setMessageId("msg-other"),
                dao -> dao.outbox.setCommandId("cmd-other"),
                dao -> dao.outbox.setDeliveryId(2L),
                dao -> dao.outbox.setAggregateType("agent"),
                dao -> dao.outbox.setAggregateId("task-other"),
                dao -> {
                    byte[] drifted = new String(
                            dao.outbox.getWirePayload(), java.nio.charset.StandardCharsets.UTF_8)
                            .replace("\"taskId\":\"task-1\"",
                                    "\"taskId\":\"task-other\"")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    dao.outbox.setWirePayload(drifted).setWirePayloadHash(sha256(drifted));
                },
                dao -> dao.outbox.setWirePayloadHash(new byte[32]));
        for (var corrupt : corruptions) {
            RecordingDao dao = new RecordingDao();
            canonicalClaimed(dao, "PENDING");
            corrupt.accept(dao);

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(dao, enabledGate())
                            .claim(message(), "worker-a", NOW, LEASE));
            assertEquals(null, dao.inbox);
        }
    }

    @Test
    void claimedTransientRejectsFrozenWireDeliveryAndExpiryDrift() {
        List<java.util.function.Consumer<RecordingDao>> corruptions = List.of(
                dao -> dao.delivery.setTaskId("task-other"),
                dao -> dao.delivery.setTargetAgentId("agent-other"),
                dao -> dao.delivery.setCommandType("WORK_ITEM_EXECUTE"),
                dao -> dao.delivery.setActiveAttempt(2),
                dao -> dao.delivery.setAttemptCount(2),
                dao -> {
                    dao.delivery.setExpiresAt(EXPIRES_AT + 1);
                    dao.outbox.setExpiresAt(EXPIRES_AT + 1);
                });
        for (var corrupt : corruptions) {
            RecordingDao dao = new RecordingDao();
            canonicalClaimed(dao, "PENDING");
            corrupt.accept(dao);

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(dao, enabledGate())
                            .claim(message(), "worker-a", NOW, LEASE));
            assertEquals(null, dao.inbox);
        }
    }

    @Test
    void claimedRetryTransientRequiresTheExactD03EligibleLaneShape() {
        List<java.util.function.Consumer<RecordingDao>> corruptions = List.of(
                dao -> dao.delivery.setNextRetryAt(null),
                dao -> dao.delivery.setNextRetryAt(NOW + 1),
                dao -> dao.delivery.setNextRetryAt(EXPIRES_AT),
                dao -> dao.outbox.setNextRetryAt(NOW - 1),
                dao -> dao.delivery.setLastError("RABBIT_NACK"));
        for (var corrupt : corruptions) {
            RecordingDao dao = new RecordingDao();
            canonicalClaimed(dao, "RETRY");
            corrupt.accept(dao);

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(dao, enabledGate())
                            .claim(message(), "worker-a", NOW, LEASE));
            assertEquals(null, dao.inbox);
        }
    }

    @Test
    void claimedTransientRejectsLeaseAttemptSettlementAndConfirmReturnFenceDrift() {
        List<java.util.function.Consumer<RecordingDao>> corruptions = List.of(
                dao -> dao.outbox.setLeaseOwner("other-relay"),
                dao -> dao.outbox.setLeaseUntil(null),
                dao -> dao.outbox.setLeaseUntil(0L),
                dao -> dao.delivery.setLeaseUntil(NOW + 29_999),
                dao -> dao.outbox.setAttemptCount(0),
                dao -> dao.outbox.setActiveAttempt(2),
                dao -> dao.outbox.setVersion(0L),
                dao -> dao.delivery.setVersion(0L),
                dao -> dao.outbox.setVersion(Long.MAX_VALUE),
                dao -> dao.delivery.setVersion(Long.MAX_VALUE),
                dao -> dao.outbox.setPublisherConfirmStatus("ACK"),
                dao -> dao.outbox.setMandatoryReturnStatus("NOT_RETURNED"),
                dao -> dao.outbox.setConfirmedAt(NOW),
                dao -> dao.outbox.setConfirmError("RABBIT_NACK"),
                dao -> dao.outbox.setReturnedAt(NOW).setReturnReplyCode(312)
                        .setReturnReplyText("NO_ROUTE"),
                dao -> dao.outbox.setPublishedAt(NOW));
        for (var corrupt : corruptions) {
            RecordingDao dao = new RecordingDao();
            canonicalClaimed(dao, "PENDING");
            corrupt.accept(dao);

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(dao, enabledGate())
                            .claim(message(), "worker-a", NOW, LEASE));
            assertEquals(null, dao.inbox);
        }
    }

    @Test
    void firstClaimUsesFrozenLockOrderAndConsumesDeliveryAtomicallyAtApiBoundary() {
        RecordingDao dao = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());

        AgentInboxClaim claim = service.claim(message(), "worker-a", NOW, LEASE);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        assertEquals(List.of("delivery", "outbox", "inbox", "insertInbox", "updateDelivery"),
                dao.operations);
        assertEquals("PROCESSING", dao.inbox.getStatus());
        assertEquals(1, dao.inbox.getAttemptCount());
        assertEquals(1, dao.inbox.getActiveAttempt());
        assertEquals(0L, dao.inbox.getVersion());
        assertEquals("CONSUMED", dao.delivery.getStatus());
        assertEquals(1L, dao.delivery.getVersion());
        assertArrayEquals(message().rawWireBytes(), dao.inbox.getWirePayload());
        assertArrayEquals(sha256(message().rawWireBytes()), dao.inbox.getWirePayloadHash());
    }

    @Test
    void publishedFirstClaimRejectsInvalidReplayAuditBeforeInboxOrDeliveryMutation() {
        for (var corrupt : replayAuditCorruptions()) {
            RecordingDao dao = new RecordingDao();
            automaticReplay(dao, "agent-1", null,
                    AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
            corrupt.accept(dao);

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(dao, enabledGate())
                            .claim(message(dao.outbox.getWirePayload()), "worker-a", NOW, LEASE));

            assertEquals(List.of("delivery", "outbox", "previous"), dao.operations);
            assertEquals(null, dao.inbox);
            assertEquals("PUBLISHED", dao.delivery.getStatus());
            assertEquals(0L, dao.delivery.getVersion());
            assertEquals("PUBLISHED", dao.outbox.getStatus());
            assertEquals(0L, dao.outbox.getVersion());
        }
    }

    @Test
    void publishedExistingPriorRejectsInvalidReplayAuditWithoutDurableMutation() {
        for (var corrupt : replayAuditCorruptions()) {
            RecordingDao dao = new RecordingDao();
            automaticReplay(dao, "agent-1", null,
                    AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
            AgentCommandInboxServiceImpl service = service(dao, enabledGate());
            AgentInboxMessage sourceMessage = message(dao.outbox.getWirePayload());
            AgentInboxClaimToken token = service
                    .claim(sourceMessage, "worker-a", NOW, LEASE).token();
            service.complete(token, AgentInboxDisposition.sent(), NOW + 1);
            corrupt.accept(dao);
            int operationCount = dao.operations.size();
            long deliveryVersion = dao.delivery.getVersion();
            long inboxVersion = dao.inbox.getVersion();

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service.claim(sourceMessage, "worker-b", NOW + 2, LEASE));

            assertEquals(List.of("delivery", "outbox", "previous"), List.copyOf(
                    dao.operations.subList(operationCount, dao.operations.size())));
            assertEquals("SENT", dao.delivery.getStatus());
            assertEquals(deliveryVersion, dao.delivery.getVersion());
            assertEquals("PROCESSED", dao.inbox.getStatus());
            assertEquals(inboxVersion, dao.inbox.getVersion());
        }
    }

    @Test
    void completionRelockRejectsReplayAuditTamperingBeforeDeliveryOrInboxMutation() {
        for (var corrupt : replayAuditCorruptions()) {
            RecordingDao dao = new RecordingDao();
            automaticReplay(dao, "agent-1", null,
                    AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
            AgentCommandInboxServiceImpl service = service(dao, enabledGate());
            AgentInboxClaimToken token = service
                    .claim(message(dao.outbox.getWirePayload()), "worker-a", NOW, LEASE).token();
            corrupt.accept(dao);
            int operationCount = dao.operations.size();
            long deliveryVersion = dao.delivery.getVersion();
            long inboxVersion = dao.inbox.getVersion();

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service.complete(token, AgentInboxDisposition.sent(), NOW + 1));

            assertEquals(List.of("delivery", "outbox", "previous", "inbox"), List.copyOf(
                    dao.operations.subList(operationCount, dao.operations.size())));
            assertEquals("CONSUMED", dao.delivery.getStatus());
            assertEquals(deliveryVersion, dao.delivery.getVersion());
            assertEquals("PROCESSING", dao.inbox.getStatus());
            assertEquals(inboxVersion, dao.inbox.getVersion());
        }
    }

    @Test
    void immediateParentIsRequiredOnFirstExistingAndCompletionPaths() {
        RecordingDao first = automaticReplay(new RecordingDao(), "agent-1", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        first.previousAttempts = List.of();
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(first, enabledGate()).claim(
                        message(first.outbox.getWirePayload()), "worker-a", NOW, LEASE));
        assertEquals(null, first.inbox);
        assertEquals("PUBLISHED", first.delivery.getStatus());

        RecordingDao existing = automaticReplay(new RecordingDao(), "agent-1", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        AgentCommandInboxServiceImpl existingService = service(existing, enabledGate());
        AgentInboxMessage existingMessage = message(existing.outbox.getWirePayload());
        AgentInboxClaimToken existingToken = existingService
                .claim(existingMessage, "worker-a", NOW, LEASE).token();
        existingService.complete(existingToken, AgentInboxDisposition.sent(), NOW + 1);
        existing.previousAttempts = List.of();
        long existingVersion = existing.delivery.getVersion();
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> existingService.claim(existingMessage, "worker-b", NOW + 2, LEASE));
        assertEquals(existingVersion, existing.delivery.getVersion());

        RecordingDao completion = automaticReplay(new RecordingDao(), "agent-1", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        AgentCommandInboxServiceImpl completionService = service(completion, enabledGate());
        AgentInboxClaimToken token = completionService.claim(
                message(completion.outbox.getWirePayload()), "worker-a", NOW, LEASE).token();
        completion.previousAttempts.getFirst().setMessageId("non-immediate-parent");
        long completionVersion = completion.delivery.getVersion();
        long inboxVersion = completion.inbox.getVersion();
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> completionService.complete(token, AgentInboxDisposition.sent(), NOW + 1));
        assertEquals(completionVersion, completion.delivery.getVersion());
        assertEquals(inboxVersion, completion.inbox.getVersion());
        assertEquals("PROCESSING", completion.inbox.getStatus());
    }

    @Test
    void attemptTwoMissingAuditFailsClosedOnFirstExistingAndCompletionPaths() {
        RecordingDao first = new RecordingDao();
        setTransportAttempt(first, 2);
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(first, enabledGate()).claim(
                        message(first.outbox.getWirePayload()), "worker-a", NOW, LEASE));
        assertEquals(List.of("delivery", "outbox", "previous"), first.operations);
        assertEquals(null, first.inbox);
        assertEquals("PUBLISHED", first.delivery.getStatus());

        RecordingDao existing = new RecordingDao();
        automaticReplay(existing, "agent-1", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        AgentCommandInboxServiceImpl existingService = service(existing, enabledGate());
        AgentInboxMessage existingMessage = message(existing.outbox.getWirePayload());
        AgentInboxClaimToken existingToken = existingService
                .claim(existingMessage, "worker-a", NOW, LEASE).token();
        existingService.complete(existingToken, AgentInboxDisposition.sent(), NOW + 1);
        clearReplayAudit(existing);
        int existingOperations = existing.operations.size();
        long existingDeliveryVersion = existing.delivery.getVersion();
        long existingInboxVersion = existing.inbox.getVersion();
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> existingService.claim(existingMessage, "worker-b", NOW + 2, LEASE));
        assertEquals(List.of("delivery", "outbox", "previous"), List.copyOf(
                existing.operations.subList(existingOperations, existing.operations.size())));
        assertEquals(existingDeliveryVersion, existing.delivery.getVersion());
        assertEquals(existingInboxVersion, existing.inbox.getVersion());

        RecordingDao completion = new RecordingDao();
        automaticReplay(completion, "agent-1", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        AgentCommandInboxServiceImpl completionService = service(completion, enabledGate());
        AgentInboxClaimToken completionToken = completionService.claim(
                message(completion.outbox.getWirePayload()), "worker-a", NOW, LEASE).token();
        clearReplayAudit(completion);
        int completionOperations = completion.operations.size();
        long completionDeliveryVersion = completion.delivery.getVersion();
        long completionInboxVersion = completion.inbox.getVersion();
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> completionService.complete(
                        completionToken, AgentInboxDisposition.sent(), NOW + 1));
        assertEquals(List.of("delivery", "outbox", "previous", "inbox"), List.copyOf(
                completion.operations.subList(completionOperations, completion.operations.size())));
        assertEquals(completionDeliveryVersion, completion.delivery.getVersion());
        assertEquals(completionInboxVersion, completion.inbox.getVersion());

        RecordingDao mismatch = new RecordingDao();
        mismatch.outbox.setActiveAttempt(2);
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(mismatch, enabledGate()).claim(
                        message(), "worker-a", NOW, LEASE));
        assertEquals(null, mismatch.inbox);
        assertEquals("PUBLISHED", mismatch.delivery.getStatus());
    }

    @Test
    void publishedClaimCompletionAndPriorAllowNullAndStrictAutomaticReplayAudits() {
        List<java.util.function.Consumer<RecordingDao>> validAudits = List.of(
                dao -> { },
                dao -> automaticReplay(dao, "agent-1", null,
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT),
                dao -> automaticReplay(dao,
                        AgentCommandReissueServiceImpl.REQUESTER_SCHEDULER, null,
                        AgentCommandReissueServiceImpl.REASON_SCHEDULER));
        for (var audit : validAudits) {
            RecordingDao dao = new RecordingDao();
            audit.accept(dao);
            AgentCommandInboxServiceImpl service = service(dao, enabledGate());

            AgentInboxMessage sourceMessage = message(dao.outbox.getWirePayload());
            AgentInboxClaim first = service.claim(sourceMessage, "worker-a", NOW, LEASE);
            assertEquals(AgentInboxClaim.Kind.ACQUIRED, first.kind());
            assertEquals("SENT", service.complete(
                    first.token(), AgentInboxDisposition.sent(), NOW + 1).resultStatus());
            assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, service.claim(
                    sourceMessage, "worker-b", NOW + 2, LEASE).kind());
        }
    }

    @Test
    void exactDuplicateIsInFlightThenReturnsPriorSchemaResultAfterSentCompletion() {
        RecordingDao dao = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());
        AgentInboxClaim first = service.claim(message(), "worker-a", NOW, LEASE);

        AgentInboxClaim inFlight = service.claim(message(), "worker-b", NOW + 1, LEASE);
        assertEquals(AgentInboxClaim.Kind.IN_FLIGHT, inFlight.kind());
        assertEquals(LEASE - 1, inFlight.retryAfterMillis());

        var completed = service.complete(
                first.token(), AgentInboxDisposition.sent(), NOW + 2);
        assertEquals("PROCESSED", completed.status());
        assertEquals("SENT", completed.resultStatus());
        assertEquals("SENT", dao.delivery.getStatus());

        AgentInboxClaim duplicate = service.claim(message(), "worker-c", NOW + 3, LEASE);
        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, duplicate.kind());
        assertEquals("PROCESSED", duplicate.priorResult().status());
        assertEquals("SENT", duplicate.priorResult().resultStatus());

        for (String advanced : List.of(
                "RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED", "EXPIRED", "DEAD")) {
            dao.delivery.setStatus(advanced);
            AgentInboxClaim advancedDuplicate = service.claim(
                    message(), "worker-d", NOW + 4, LEASE);
            assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, advancedDuplicate.kind(), advanced);
            assertEquals("SENT", advancedDuplicate.priorResult().resultStatus(), advanced);
            assertEquals(1, dao.inbox.getAttemptCount(), advanced);
        }
    }

    @Test
    void callerBytesMetadataAndStoredCorruptionConflictWithoutOverwrite() {
        RecordingDao changedBytes = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(changedBytes, enabledGate());
        byte[] original = Arrays.copyOf(changedBytes.outbox.getWirePayload(),
                changedBytes.outbox.getWirePayload().length);
        AgentInboxMessage different = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1,
                "different".getBytes());
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(different, "worker-a", NOW, LEASE));
        assertArrayEquals(original, changedBytes.outbox.getWirePayload());
        assertEquals(null, changedBytes.inbox);

        RecordingDao metadata = new RecordingDao();
        AgentCommandInboxServiceImpl metadataService = service(metadata, enabledGate());
        AgentInboxMessage wrongEvent = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-other", "cmd-1", 1,
                message().rawWireBytes());
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> metadataService.claim(wrongEvent, "worker-a", NOW, LEASE));
        for (AgentInboxMessage drift : List.of(
                new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                        "tenant-a", "client-a", "msg-other", "evt-1", "cmd-1", 1,
                        message().rawWireBytes()),
                new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                        "tenant-a", "client-a", "msg-1", "evt-1", "cmd-other", 1,
                        message().rawWireBytes()),
                new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                        "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 2,
                        message().rawWireBytes()),
                new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                        "tenant-A", "client-a", "msg-1", "evt-1", "cmd-1", 1,
                        message().rawWireBytes()))) {
            RecordingDao driftDao = new RecordingDao();
            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(driftDao, enabledGate())
                            .claim(drift, "worker-a", NOW, LEASE));
            assertEquals(null, driftDao.inbox);
            assertEquals("PUBLISHED", driftDao.delivery.getStatus());
        }
        assertEquals(null, metadata.inbox);

        RecordingDao corruption = new RecordingDao();
        corruption.outbox.setWirePayloadHash(new byte[32]);
        AgentCommandInboxServiceImpl corruptionService = service(corruption, enabledGate());
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> corruptionService.claim(message(), "worker-a", NOW, LEASE));
        assertEquals(null, corruption.inbox);
    }

    @Test
    void inboxStoredHashCorruptionFailsClosedForDuplicate() {
        RecordingDao dao = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());
        service.claim(message(), "worker-a", NOW, LEASE);
        dao.inbox.setWirePayloadHash(new byte[32]);

        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-b", NOW + 1, LEASE));
        assertEquals("PROCESSING", dao.inbox.getStatus());
        assertEquals(1, dao.inbox.getAttemptCount());
    }

    @Test
    void expiryBoundaryWritesExpiredInboxAndCurrentDeliveryOnly() {
        RecordingDao dao = new RecordingDao(NOW);
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());

        AgentInboxClaim claim = service.claim(message(NOW), "worker-a", NOW, LEASE);

        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, claim.kind());
        assertEquals("EXPIRED", claim.priorResult().status());
        assertEquals("EXPIRED", dao.delivery.getStatus());
        assertEquals(0, dao.inbox.getAttemptCount());
    }

    @Test
    void staleOldMessagePersistsDeadInboxWithoutRegressingCurrentDelivery() {
        RecordingDao dao = new RecordingDao();
        dao.delivery.setActiveMessageId("msg-new");
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());

        AgentInboxClaim claim = service.claim(message(), "worker-a", NOW, LEASE);

        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, claim.kind());
        assertEquals("DEAD", claim.priorResult().status());
        assertEquals(AgentCommandInboxServiceImpl.STALE_MESSAGE_FENCE,
                claim.priorResult().lastError());
        assertEquals("PUBLISHED", dao.delivery.getStatus());
        assertEquals("msg-new", dao.delivery.getActiveMessageId());
    }

    @Test
    void outboxPublishAttemptCountIsIndependentFromDeliveryTransportFence() {
        RecordingDao dao = new RecordingDao();
        dao.outbox.setAttemptCount(7);
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());

        AgentInboxClaim claim = service.claim(message(), "worker-a", NOW, LEASE);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        assertEquals(1, claim.token().deliveryActiveAttempt());
        assertEquals("CONSUMED", dao.delivery.getStatus());
    }

    @Test
    void expiredLeaseReclaimHasOneWinnerAndFencesOldCompletion() {
        RecordingDao dao = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());
        AgentInboxClaim first = service.claim(message(), "worker-a", NOW, LEASE);

        AgentInboxClaim reclaimed = service.claim(message(), "worker-b", NOW + LEASE, LEASE);
        AgentInboxClaim loser = service.claim(message(), "worker-c", NOW + LEASE + 1, LEASE);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, reclaimed.kind());
        assertEquals(2, reclaimed.token().activeAttempt());
        assertEquals(1L, reclaimed.token().inboxVersion());
        assertEquals(AgentInboxClaim.Kind.IN_FLIGHT, loser.kind());
        assertThrows(AgentInboxFenceException.class,
                () -> service.complete(first.token(), AgentInboxDisposition.sent(), NOW + LEASE + 2));
        assertEquals("PROCESSING", dao.inbox.getStatus());
    }

    @Test
    void retryDispositionRequiresEligibilityAndReclaimsWithNewDeliveryFence() {
        RecordingDao dao = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());
        AgentInboxClaim first = service.claim(message(), "worker-a", NOW, LEASE);
        long retryAt = NOW + 20_000;
        service.complete(first.token(), new AgentInboxDisposition(
                AgentInboxDisposition.Type.RETRY, retryAt, "WS_TRANSIENT"), NOW + 1);

        AgentInboxClaim early = service.claim(message(), "worker-b", retryAt - 1, LEASE);
        AgentInboxClaim eligible = service.claim(message(), "worker-b", retryAt, LEASE);

        assertEquals(AgentInboxClaim.Kind.IN_FLIGHT, early.kind());
        assertEquals(1L, early.retryAfterMillis());
        assertEquals(AgentInboxClaim.Kind.ACQUIRED, eligible.kind());
        assertEquals("CONSUMED", dao.delivery.getStatus());
        assertEquals(3L, dao.delivery.getVersion());
        assertEquals(2, dao.inbox.getAttemptCount());
    }

    @Test
    void everyBoundedDispositionMapsBothTablesAndSentNeverMeansReceived() {
        List<AgentInboxDisposition> dispositions = List.of(
                AgentInboxDisposition.sent(),
                new AgentInboxDisposition(AgentInboxDisposition.Type.WAITING_AGENT,
                        NOW + 20_000, "AGENT_OFFLINE"),
                new AgentInboxDisposition(AgentInboxDisposition.Type.RETRY,
                        NOW + 20_000, "WS_TRANSIENT"),
                new AgentInboxDisposition(AgentInboxDisposition.Type.FAILED,
                        null, "WS_FAILED"),
                new AgentInboxDisposition(AgentInboxDisposition.Type.DEAD,
                        null, "INVALID_TARGET"));
        for (AgentInboxDisposition disposition : dispositions) {
            RecordingDao dao = new RecordingDao();
            AgentCommandInboxServiceImpl service = service(dao, enabledGate());
            AgentInboxClaim claim = service.claim(message(), "worker-a", NOW, LEASE);

            var result = service.complete(claim.token(), disposition, NOW + 1);

            String expectedInbox = disposition.type() == AgentInboxDisposition.Type.SENT
                    ? "PROCESSED" : disposition.type().name();
            assertEquals(expectedInbox, result.status());
            assertEquals(disposition.type().name(), result.resultStatus());
            assertEquals(disposition.type().name(), dao.delivery.getStatus());
            assertFalse("RECEIVED".equals(dao.delivery.getStatus()));
        }
    }

    @Test
    void completionRechecksOutboxStatusButNotIndependentPublishAttemptCount() {
        RecordingDao statusDrift = new RecordingDao();
        AgentCommandInboxServiceImpl statusService = service(statusDrift, enabledGate());
        AgentInboxClaimToken statusToken = statusService
                .claim(message(), "worker-a", NOW, LEASE).token();
        statusDrift.outbox.setStatus("RETRY");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> statusService.complete(
                        statusToken, AgentInboxDisposition.sent(), NOW + 1));
        assertEquals("CONSUMED", statusDrift.delivery.getStatus());
        assertEquals("PROCESSING", statusDrift.inbox.getStatus());

        RecordingDao publishAttempt = new RecordingDao();
        AgentCommandInboxServiceImpl attemptService = service(publishAttempt, enabledGate());
        AgentInboxClaimToken attemptToken = attemptService
                .claim(message(), "worker-a", NOW, LEASE).token();
        publishAttempt.outbox.setAttemptCount(7);
        var completed = attemptService.complete(
                attemptToken, AgentInboxDisposition.sent(), NOW + 1);
        assertEquals("SENT", completed.resultStatus());
        assertEquals("SENT", publishAttempt.delivery.getStatus());
        assertEquals("PROCESSED", publishAttempt.inbox.getStatus());
    }

    @Test
    void expiredCompletionRejectsEarlyAndCommitsAtAuthoritativeBoundary() {
        RecordingDao dao = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());
        AgentInboxClaimToken token = service.claim(
                message(), "worker-a", NOW, 60_000).token();
        AgentInboxDisposition expired = new AgentInboxDisposition(
                AgentInboxDisposition.Type.EXPIRED, null, "MESSAGE_EXPIRED");

        assertThrows(IllegalArgumentException.class,
                () -> service.complete(token, expired, NOW + 1));
        assertEquals("PROCESSING", dao.inbox.getStatus());
        assertEquals("CONSUMED", dao.delivery.getStatus());

        var result = service.complete(token, expired, NOW + 60_000);
        assertEquals("EXPIRED", result.status());
        assertEquals("EXPIRED", dao.delivery.getStatus());
    }

    @Test
    void staleMarkerAndTerminalShapeCorruptionFailClosed() {
        RecordingDao stale = new RecordingDao();
        stale.delivery.setActiveMessageId("msg-new");
        AgentCommandInboxServiceImpl staleService = service(stale, enabledGate());
        assertEquals("DEAD", staleService.claim(
                message(), "worker-a", NOW, LEASE).priorResult().status());
        stale.delivery.setActiveMessageId("msg-1");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> staleService.claim(message(), "worker-b", NOW + 1, LEASE));
        assertEquals("DEAD", stale.inbox.getStatus());

        List<java.util.function.Consumer<AgentConsumerInboxEntity>> corruptions = List.of(
                inbox -> inbox.setProcessedAt(null),
                inbox -> inbox.setLeaseOwner("ghost-owner").setLeaseUntil(NOW + LEASE),
                inbox -> inbox.setNextRetryAt(NOW + 20_000),
                inbox -> inbox.setLastError("CORRUPT_SENT"));
        for (var corrupt : corruptions) {
            RecordingDao dao = new RecordingDao();
            AgentCommandInboxServiceImpl service = service(dao, enabledGate());
            AgentInboxClaimToken token = service.claim(
                    message(), "worker-a", NOW, LEASE).token();
            service.complete(token, AgentInboxDisposition.sent(), NOW + 1);
            corrupt.accept(dao.inbox);

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service.claim(message(), "worker-b", NOW + 2, LEASE));
            assertEquals("SENT", dao.delivery.getStatus());
        }
    }

    @Test
    void storedFailureExpiryAndRetryShapesFailClosed() {
        RecordingDao expired = new RecordingDao();
        AgentCommandInboxServiceImpl expiredService = service(expired, enabledGate());
        AgentInboxClaimToken expiredToken = expiredService.claim(
                message(), "worker-a", NOW, 60_000).token();
        expiredService.complete(expiredToken, new AgentInboxDisposition(
                AgentInboxDisposition.Type.EXPIRED, null, "MESSAGE_EXPIRED"), NOW + 60_000);
        expired.inbox.setProcessedAt(NOW + 59_999);
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> expiredService.claim(message(), "worker-b", NOW + 60_001, LEASE));
        assertEquals("EXPIRED", expired.delivery.getStatus());

        RecordingDao failed = new RecordingDao();
        AgentCommandInboxServiceImpl failedService = service(failed, enabledGate());
        AgentInboxClaimToken failedToken = failedService.claim(
                message(), "worker-a", NOW, LEASE).token();
        failedService.complete(failedToken, new AgentInboxDisposition(
                AgentInboxDisposition.Type.FAILED, null, "WS_FAILED"), NOW + 1);
        failed.inbox.setLastError(null);
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> failedService.claim(message(), "worker-b", NOW + 2, LEASE));
        failed.inbox.setLastError(" ");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> failedService.claim(message(), "worker-b", NOW + 3, LEASE));
        assertEquals("FAILED", failed.delivery.getStatus());

        RecordingDao retry = new RecordingDao();
        AgentCommandInboxServiceImpl retryService = service(retry, enabledGate());
        AgentInboxClaimToken retryToken = retryService.claim(
                message(), "worker-a", NOW, LEASE).token();
        retryService.complete(retryToken, new AgentInboxDisposition(
                AgentInboxDisposition.Type.RETRY, NOW + 20_000, "WS_TRANSIENT"), NOW + 1);
        retry.inbox.setNextRetryAt(retry.inbox.getProcessedAt());
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> retryService.claim(message(), "worker-b", NOW + 2, LEASE));
        assertEquals("RETRY", retry.delivery.getStatus());
    }

    @Test
    void historicalDbShadowRowsNeverCreateInboxOrPromoteDelivery() {
        RecordingDao dao = new RecordingDao();
        dao.delivery.setStatus("DEAD").setLastError(AgentCommandInboxServiceImpl.DB_SHADOW_MARKER);
        dao.outbox.setStatus("DEAD").setLastError(AgentCommandInboxServiceImpl.DB_SHADOW_MARKER);
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());

        AgentInboxClaim claim = service.claim(message(), "worker-a", NOW, LEASE);

        assertEquals(AgentInboxClaim.Kind.DISABLED, claim.kind());
        assertEquals(AgentInboxClaim.DisabledReason.DB_SHADOW_CAPTURE_ONLY,
                claim.disabledReason());
        assertEquals(null, dao.inbox);
        assertEquals("DEAD", dao.delivery.getStatus());
    }

    @Test
    void consumerNameMustBeFrozenStableAsciiLogicalName() {
        RecordingDao dao = new RecordingDao();
        AgentCommandInboxServiceImpl service = service(dao, enabledGate());
        for (String invalid : List.of(
                "agent-command-dispatch-v1@host-1", "pid-1234", "session-abc",
                "consumerTag-42", "agent-command-dispatch-v1 ",
                "agent-command-dispatch-v１")) {
            AgentInboxMessage message = new AgentInboxMessage(
                    invalid, "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1,
                    "wire".getBytes());
            assertThrows(IllegalArgumentException.class,
                    () -> service.claim(message, "worker-a", NOW, LEASE), invalid);
        }
        assertEquals(0, dao.accesses);
    }

    private AgentCommandInboxServiceImpl service(RecordingDao dao, AgentRabbitSafetyGate gate) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:d07_unit_" + System.nanoTime());
        return new AgentCommandInboxServiceImpl(
                dao, gate, new DataSourceTransactionManager(source));
    }

    private AgentInboxMessage message() {
        return message(EXPIRES_AT);
    }

    private AgentInboxMessage message(long expiresAt) {
        return message(wire(expiresAt));
    }

    private AgentInboxMessage message(byte[] rawWireBytes) {
        return new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1,
                rawWireBytes);
    }

    private static byte[] wire(long expiresAt) {
        return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\","
                + "\"tenantId\":\"tenant-a\",\"clientId\":\"client-a\","
                + "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\","
                + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,"
                + "\"expiresAt\":" + expiresAt + ",\"payload\":{}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void canonicalClaimed(RecordingDao dao, String deliveryStatus) {
        dao.delivery.setStatus(deliveryStatus)
                .setNextRetryAt("RETRY".equals(deliveryStatus) ? NOW - 1 : null)
                .setLeaseOwner("d03-relay").setLeaseUntil(NOW + 30_000)
                .setLastError(null).setVersion(1L);
        dao.outbox.setStatus("CLAIMED").setAttemptCount(1).setActiveAttempt(1)
                .setNextRetryAt(null).setLeaseOwner("d03-relay").setLeaseUntil(NOW + 30_000)
                .setPublisherConfirmStatus("PENDING").setConfirmedAt(null).setConfirmError(null)
                .setMandatoryReturnStatus("PENDING").setReturnedAt(null)
                .setReturnReplyCode(null).setReturnReplyText(null).setPublishedAt(null)
                .setLastError(null).setVersion(1L);
    }

    @Test
    void claimedSourceAcceptsOnlyExactD06ReplayAuditDuringPublishSettlementRace() {
        RecordingDao canonical = new RecordingDao();
        canonicalClaimed(canonical, "PENDING");
        automaticReplay(canonical, "agent-1", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        assertThrows(AgentInboxSourceNotSettledException.class,
                () -> service(canonical, enabledGate())
                        .claim(message(canonical.outbox.getWirePayload()),
                                "worker-a", NOW, LEASE));

        RecordingDao scheduler = new RecordingDao();
        canonicalClaimed(scheduler, "PENDING");
        automaticReplay(scheduler, AgentCommandReissueServiceImpl.REQUESTER_SCHEDULER, null,
                AgentCommandReissueServiceImpl.REASON_SCHEDULER);
        assertThrows(AgentInboxSourceNotSettledException.class,
                () -> service(scheduler, enabledGate())
                        .claim(message(scheduler.outbox.getWirePayload()),
                                "worker-a", NOW, LEASE));

        for (var corrupt : replayAuditCorruptions()) {
            RecordingDao invalid = new RecordingDao();
            canonicalClaimed(invalid, "PENDING");
            automaticReplay(invalid, "agent-1", null,
                    AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
            corrupt.accept(invalid);
            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(invalid, enabledGate())
                            .claim(message(invalid.outbox.getWirePayload()),
                                    "worker-a", NOW, LEASE));
            assertEquals(List.of("delivery", "outbox", "previous"), invalid.operations);
            assertEquals(null, invalid.inbox);
        }
    }

    private static List<java.util.function.Consumer<RecordingDao>> replayAuditCorruptions() {
        return List.of(
                dao -> dao.outbox.setReplayParentMessageId(null)
                        .setReplayRequesterId(null).setReplayApproverId(null)
                        .setReplayReason(null),
                dao -> dao.outbox.setReplayParentMessageId("msg-other"),
                dao -> replayAudit(dao, "operator", null,
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT),
                dao -> replayAudit(dao, "agent-other", null,
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT),
                dao -> replayAudit(dao, "agent-1", null,
                        AgentCommandReissueServiceImpl.REASON_SCHEDULER),
                dao -> replayAudit(dao, "agent-1", "manual-approver",
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT));
    }

    private static void setTransportAttempt(RecordingDao dao, int attempt) {
        byte[] wire = new String(wire(dao.delivery.getExpiresAt()),
                java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"attempt\":1", "\"attempt\":" + attempt)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] wireHash = sha256(wire);
        dao.delivery.setAttemptCount(attempt).setActiveAttempt(attempt);
        dao.outbox.setActiveAttempt(attempt).setWirePayload(wire).setWirePayloadHash(wireHash);
        if (dao.inbox != null) {
            dao.inbox.setWirePayload(wire).setWirePayloadHash(wireHash);
        }
        if (attempt > 1) {
            byte[] parentWire = new String(wire(dao.delivery.getExpiresAt()),
                    java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\"messageId\":\"msg-1\"",
                            "\"messageId\":\"msg-parent\"")
                    .replace("\"attempt\":1", "\"attempt\":" + (attempt - 1))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var route = cn.jia.agent.config.AgentRabbitTopologyManifest.canonical()
                    .defaultCommandPublishRoute();
            AgentOutboxEventEntity parent = new AgentOutboxEventEntity()
                    .setId(1L).setEventId("evt-parent").setMessageId("msg-parent")
                    .setCommandId("cmd-1").setDeliveryId(1L)
                    .setAggregateType("task").setAggregateId("task-1")
                    .setDestination(route.destination()).setRoutingKey(route.routingKey())
                    .setWirePayload(parentWire).setWirePayloadHash(sha256(parentWire))
                    .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(attempt - 1)
                    .setExpiresAt(dao.delivery.getExpiresAt()).setPublisherConfirmStatus("ACK")
                    .setConfirmedAt(NOW - 20).setMandatoryReturnStatus("NOT_RETURNED")
                    .setPublishedAt(NOW - 19).setVersion(1L);
            parent.setTenantId("tenant-a"); parent.setClientId("client-a");
            dao.previousAttempts = List.of(parent);
        }
    }

    private static RecordingDao automaticReplay(
            RecordingDao dao, String requester, String approver, String reason) {
        setTransportAttempt(dao, 2);
        return replayAudit(dao, requester, approver, reason);
    }

    private static void clearReplayAudit(RecordingDao dao) {
        dao.delivery.setReplayParentMessageId(null).setReplayRequesterId(null)
                .setReplayApproverId(null).setReplayReason(null);
        dao.outbox.setReplayParentMessageId(null).setReplayRequesterId(null)
                .setReplayApproverId(null).setReplayReason(null);
    }

    private static RecordingDao replayAudit(
            RecordingDao dao, String requester, String approver, String reason) {
        dao.delivery.setReplayParentMessageId("msg-parent")
                .setReplayRequesterId(requester).setReplayApproverId(approver)
                .setReplayReason(reason);
        dao.outbox.setReplayParentMessageId("msg-parent")
                .setReplayRequesterId(requester).setReplayApproverId(approver)
                .setReplayReason(reason);
        return dao;
    }

    private AgentRabbitSafetyGate offGate() {
        return gate(false, false, false);
    }

    private AgentRabbitSafetyGate dbShadowGate() {
        return gate(true, false, false);
    }

    private AgentRabbitSafetyGate consumeDisabledGate() {
        return gate(true, true, false);
    }

    private AgentRabbitSafetyGate enabledGate() {
        return gate(true, true, true);
    }

    private AgentRabbitSafetyGate gate(boolean outbox, boolean topology, boolean consume) {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(outbox),
                new AgentRabbitSafetyProperties.RabbitTopology(topology),
                new AgentRabbitSafetyProperties.RabbitPublish(false),
                new AgentRabbitSafetyProperties.RabbitConsume(consume),
                new AgentRabbitSafetyProperties.RabbitDispatch(false),
                topology ? new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "d07-user", "d07-pass", "/d07") : null));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static final class RecordingDao implements AgentCommandInboxDao {
        private final List<String> operations = new ArrayList<>();
        private AgentCommandDeliveryEntity delivery;
        private AgentOutboxEventEntity outbox;
        private AgentConsumerInboxEntity inbox;
        private List<AgentOutboxEventEntity> previousAttempts = List.of();
        private int accesses;

        private RecordingDao() {
            this(EXPIRES_AT);
        }

        private RecordingDao(long expiresAt) {
            byte[] wire = wire(expiresAt);
            delivery = new AgentCommandDeliveryEntity()
                    .setId(1L).setCommandId("cmd-1").setTaskId("task-1")
                    .setTargetAgentId("agent-1").setCommandType("TASK_INVITE")
                    .setCommandPayload("business".getBytes())
                    .setCommandPayloadHash(sha256("business".getBytes()))
                    .setStatus("PUBLISHED").setAttemptCount(1).setNextRetryAt(null)
                    .setLeaseOwner(null).setLeaseUntil(null)
                    .setActiveMessageId("msg-1").setActiveAttempt(1)
                    .setExpiresAt(expiresAt).setLastError(null).setVersion(0L);
            delivery.setTenantId("tenant-a");
            delivery.setClientId("client-a");
            var route = cn.jia.agent.config.AgentRabbitTopologyManifest.canonical()
                    .defaultCommandPublishRoute();
            outbox = new AgentOutboxEventEntity()
                    .setId(2L).setEventId("evt-1").setMessageId("msg-1")
                    .setCommandId("cmd-1").setDeliveryId(1L)
                    .setAggregateType("task").setAggregateId("task-1")
                    .setDestination(route.destination()).setRoutingKey(route.routingKey())
                    .setWirePayload(wire).setWirePayloadHash(sha256(wire))
                    .setStatus("PUBLISHED").setAttemptCount(1).setNextRetryAt(null)
                    .setLeaseOwner(null).setLeaseUntil(null).setActiveAttempt(1)
                    .setExpiresAt(expiresAt).setPublisherConfirmStatus("ACK")
                    .setMandatoryReturnStatus("NOT_RETURNED").setVersion(0L);
            outbox.setTenantId("tenant-a");
            outbox.setClientId("client-a");
        }

        @Override
        public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId) {
            accessed("delivery");
            return exact(tenantId, clientId) && this.delivery.getId() == deliveryId
                    ? this.delivery : null;
        }

        @Override
        public AgentOutboxEventEntity lockOutbox(String tenantId, String clientId, String eventId) {
            accessed("outbox");
            return exact(tenantId, clientId) && Objects.equals(outbox.getEventId(), eventId)
                    ? outbox : null;
        }

        @Override
        public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
                String tenantId, String clientId, long deliveryId, int previousAttempt) {
            accessed("previous");
            return previousAttempts;
        }

        @Override
        public AgentConsumerInboxEntity lockInbox(
                String tenantId, String clientId, String consumerName, String messageId) {
            accessed("inbox");
            if (inbox == null) return null;
            return exact(tenantId, clientId)
                    && Objects.equals(inbox.getConsumerName(), consumerName)
                    && Objects.equals(inbox.getMessageId(), messageId) ? inbox : null;
        }

        @Override
        public int insertInbox(AgentConsumerInboxEntity value) {
            accessed("insertInbox");
            if (inbox != null) return 0;
            value.setId(3L);
            inbox = value;
            return 1;
        }

        @Override
        public int updateDeliveryDisposition(
                String tenantId, String clientId, long deliveryId,
                String activeMessageId, int activeAttempt,
                String expectedStatus, long expectedVersion,
                String newStatus, Long nextRetryAt, String lastError, long now) {
            accessed("updateDelivery");
            if (!exact(tenantId, clientId) || delivery.getId() != deliveryId
                    || !Objects.equals(delivery.getActiveMessageId(), activeMessageId)
                    || !Objects.equals(delivery.getActiveAttempt(), activeAttempt)
                    || !Objects.equals(delivery.getStatus(), expectedStatus)
                    || !Objects.equals(delivery.getVersion(), expectedVersion)) return 0;
            delivery.setStatus(newStatus).setNextRetryAt(nextRetryAt)
                    .setLastError(lastError).setVersion(expectedVersion + 1);
            return 1;
        }

        @Override
        public int reclaimProcessingInbox(
                AgentConsumerInboxEntity expected, String newLeaseOwner, long newLeaseUntil, long now) {
            accessed("reclaimProcessing");
            if (inbox != expected || !"PROCESSING".equals(inbox.getStatus())) return 0;
            inbox.setAttemptCount(inbox.getAttemptCount() + 1)
                    .setActiveAttempt(inbox.getActiveAttempt() + 1)
                    .setVersion(inbox.getVersion() + 1)
                    .setLeaseOwner(newLeaseOwner).setLeaseUntil(newLeaseUntil)
                    .setNextRetryAt(null).setProcessedAt(null).setLastError(null);
            return 1;
        }

        @Override
        public int reclaimRetryInbox(
                AgentConsumerInboxEntity expected, String newLeaseOwner, long newLeaseUntil, long now) {
            accessed("reclaimRetry");
            if (inbox != expected || !"RETRY".equals(inbox.getStatus())) return 0;
            inbox.setStatus("PROCESSING").setResultStatus(null)
                    .setAttemptCount(inbox.getAttemptCount() + 1)
                    .setActiveAttempt(inbox.getActiveAttempt() + 1)
                    .setVersion(inbox.getVersion() + 1)
                    .setLeaseOwner(newLeaseOwner).setLeaseUntil(newLeaseUntil)
                    .setNextRetryAt(null).setProcessedAt(null).setLastError(null);
            return 1;
        }

        @Override
        public int expireRetryInbox(
                AgentConsumerInboxEntity expected, long processedAt, String lastError, long now) {
            accessed("expireRetry");
            if (inbox != expected || !"RETRY".equals(inbox.getStatus())) return 0;
            inbox.setStatus("EXPIRED").setResultStatus("EXPIRED")
                    .setNextRetryAt(null).setProcessedAt(processedAt)
                    .setLastError(lastError).setVersion(inbox.getVersion() + 1);
            return 1;
        }

        @Override
        public int completeInbox(
                long inboxId, String tenantId, String clientId,
                String consumerName, String messageId,
                String leaseOwner, long leaseUntil, int activeAttempt, long expectedVersion,
                String newStatus, String resultStatus, Long nextRetryAt,
                long processedAt, String lastError, long now) {
            accessed("completeInbox");
            if (inbox == null || inbox.getId() != inboxId || !exact(tenantId, clientId)
                    || !Objects.equals(inbox.getConsumerName(), consumerName)
                    || !Objects.equals(inbox.getMessageId(), messageId)
                    || !Objects.equals(inbox.getLeaseOwner(), leaseOwner)
                    || !Objects.equals(inbox.getLeaseUntil(), leaseUntil)
                    || !Objects.equals(inbox.getActiveAttempt(), activeAttempt)
                    || !Objects.equals(inbox.getVersion(), expectedVersion)
                    || !"PROCESSING".equals(inbox.getStatus())) return 0;
            inbox.setStatus(newStatus).setResultStatus(resultStatus)
                    .setNextRetryAt(nextRetryAt).setLeaseOwner(null).setLeaseUntil(null)
                    .setProcessedAt(processedAt).setLastError(lastError)
                    .setVersion(expectedVersion + 1);
            return 1;
        }

        private boolean exact(String tenantId, String clientId) {
            return Objects.equals(delivery.getTenantId(), tenantId)
                    && Objects.equals(delivery.getClientId(), clientId);
        }

        private void accessed(String operation) {
            accesses++;
            operations.add(operation);
        }
    }
}
