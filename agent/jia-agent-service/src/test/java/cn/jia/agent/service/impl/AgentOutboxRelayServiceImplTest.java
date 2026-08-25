package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOutboxRelayServiceImplTest {
    private static final long NOW = 1_000_000L;
    private static final long EXPIRES = 9_999_999L;
    private static final AgentRabbitTopologyManifest MANIFEST = AgentRabbitTopologyManifest.canonical();

    private AgentOutboxRelayDao dao;
    private AgentOutboxRelayServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        dao = mock(AgentOutboxRelayDao.class);
        when(dao.claimDelivery(any(), anyString(), anyLong(), isNull(), anyLong())).thenReturn(1);
        when(dao.claimOutbox(any(), anyString(), anyLong(), isNull(), anyLong())).thenReturn(1);
        when(dao.disposeDelivery(any(), anyString(), any(), any(), anyLong())).thenReturn(1);
        when(dao.disposeOutbox(any(), anyString(), any(), anyString(), any(), any(),
                anyString(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1);
        when(dao.quarantineDelivery(any(), anyString(), anyLong())).thenReturn(1);
        when(dao.quarantineOutbox(any(), anyString(), anyLong())).thenReturn(1);
        service = service(allowedGate(), ready());
    }

    @Test
    void discoveryMergesDueAndStaleLanesDeterministicallyWithOverscan() {
        when(dao.selectDueCandidates(NOW, 8)).thenReturn(List.of(
                candidate(8, 80), candidate(3, 30)));
        when(dao.selectStaleCandidates(NOW, 8)).thenReturn(List.of(
                candidate(4, 30), candidate(2, 20)));

        List<AgentOutboxCandidate> found = service.discover(NOW, 2);

        assertEquals(List.of(2L, 3L, 4L, 8L),
                found.stream().map(AgentOutboxCandidate::outboxId).toList());
        verify(dao).selectDueCandidates(NOW, 8);
        verify(dao).selectStaleCandidates(NOW, 8);
    }

    @Test
    void mqShadowAndNotReadyPerformZeroDatabaseAccess() throws Exception {
        AgentOutboxRelayServiceImpl shadow = service(mqShadowGate(), ready());
        assertEquals(List.of(), shadow.discover(NOW, 50));
        assertEquals(AgentOutboxClaim.Status.DISABLED,
                shadow.claim(candidate(1, NOW), "lease", NOW).status());
        AgentOutboxRelayServiceImpl notReady = service(allowedGate(), notReady());
        assertEquals(List.of(), notReady.discover(NOW, 50));
        assertEquals(AgentOutboxClaim.Status.DISABLED,
                notReady.claim(candidate(1, NOW), "lease", NOW).status());
        verify(dao, never()).selectCorruptCandidates(anyLong(), anyInt());
        verify(dao, never()).selectDueCandidates(anyLong(), anyInt());
        verify(dao, never()).lockDelivery(anyString(), anyString(), anyLong());
    }

    @Test
    void validPendingClaimUsesDeliveryThenOutboxLocksAndAdvancesPublishFenceOnly() {
        Fixture fixture = pending();
        arrange(fixture);

        AgentOutboxClaim claim = service.claim(candidate(1, NOW), "lease-a", NOW);

        assertEquals(AgentOutboxClaim.Status.ACQUIRED, claim.status());
        AgentOutboxClaimToken token = claim.token();
        assertEquals(1, token.publishAttempt());
        assertEquals(1, token.deliveryActiveAttempt());
        assertArrayEquals(fixture.outbox.getWirePayload(), token.wirePayload());
        InOrder ordered = inOrder(dao);
        ordered.verify(dao).lockDelivery("tenant-a", "client-a", 41L);
        ordered.verify(dao).lockOutbox("tenant-a", "client-a", 1L);
        ordered.verify(dao).claimDelivery(eq(fixture.delivery), eq("lease-a"),
                eq(NOW + 30_000L), isNull(), eq(NOW));
        ordered.verify(dao).claimOutbox(eq(fixture.outbox), eq("lease-a"),
                eq(NOW + 30_000L), isNull(), eq(NOW));
        assertEquals(1, fixture.delivery.getAttemptCount());
    }


    @Test
    void exactAutomaticReissueReplayProvenanceIsClaimableButManualOrPartialHasZeroMutation() {
        Fixture automatic = automaticReplay(
                "agent-1", null, AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        arrange(automatic);
        assertEquals(AgentOutboxClaim.Status.ACQUIRED,
                service.claim(candidate(1, NOW), "lease-a", NOW).status());

        reset(dao);
        stubMutationSuccess();
        Fixture manual = pending();
        manual.delivery.setReplayParentMessageId("msg-parent")
                .setReplayRequesterId("operator")
                .setReplayApproverId("approver")
                .setReplayReason("MANUAL_REISSUE");
        manual.outbox.setReplayParentMessageId("msg-parent")
                .setReplayRequesterId("operator")
                .setReplayApproverId("approver")
                .setReplayReason("MANUAL_REISSUE");
        arrange(manual);
        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service.claim(candidate(1, NOW), "lease-a", NOW).status());
        verifyNoRelayMutation();

        reset(dao);
        stubMutationSuccess();
        Fixture partial = pending();
        partial.delivery.setReplayParentMessageId("msg-parent")
                .setReplayRequesterId("agent-1")
                .setReplayReason(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        arrange(partial);
        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service.claim(candidate(1, NOW), "lease-a", NOW).status());
        verifyNoRelayMutation();
    }

    @Test
    void relayRejectsMissingOrContradictoryAttemptAuditWithoutMutation() {
        Fixture missingAudit = pending();
        missingAudit.delivery.setAttemptCount(2).setActiveAttempt(2);
        missingAudit.outbox.setActiveAttempt(2);
        byte[] attemptTwoWire = new String(missingAudit.outbox.getWirePayload(),
                StandardCharsets.UTF_8)
                .replace("\"attempt\":1", "\"attempt\":2")
                .getBytes(StandardCharsets.UTF_8);
        missingAudit.outbox.setWirePayload(attemptTwoWire)
                .setWirePayloadHash(AgentCommandAmqpContract.sha256(attemptTwoWire));
        arrange(missingAudit);
        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service.claim(candidate(1, NOW), "lease-a", NOW).status());
        verifyNoRelayMutation();

        reset(dao);
        stubMutationSuccess();
        Fixture mismatch = pending();
        mismatch.outbox.setActiveAttempt(2);
        arrange(mismatch);
        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service.claim(candidate(1, NOW), "lease-a", NOW).status());
        verifyNoRelayMutation();

        reset(dao);
        stubMutationSuccess();
        Fixture attemptOneAudit = pending();
        attemptOneAudit.delivery.setReplayParentMessageId("msg-parent")
                .setReplayRequesterId("agent-1")
                .setReplayReason(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        attemptOneAudit.outbox.setReplayParentMessageId("msg-parent")
                .setReplayRequesterId("agent-1")
                .setReplayReason(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        arrange(attemptOneAudit);
        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service.claim(candidate(1, NOW), "lease-a", NOW).status());
        verifyNoRelayMutation();
    }

    @Test
    void automaticReplayRequesterReasonBindingRejectsEveryDisguisedLaneWithoutMutation() {
        List<Fixture> invalid = List.of(
                automaticReplay("operator", null,
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT),
                automaticReplay("agent-other", null,
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT),
                automaticReplay("agent-1", null,
                        AgentCommandReissueServiceImpl.REASON_SCHEDULER),
                automaticReplay("agent-1", "manual-approver",
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT));
        for (Fixture fixture : invalid) {
            reset(dao);
            stubMutationSuccess();
            arrange(fixture);

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service.claim(candidate(1, NOW), "lease-a", NOW).status());

            verifyNoRelayMutation();
        }
    }

    @Test
    void historicalPendingWithoutExactAdmissionMarkerBecomesDeadWithoutPublishToken() {
        Fixture fixture = pending();
        fixture.outbox.setLastError(null);
        arrange(fixture);

        AgentOutboxClaim claim = service.claim(candidate(1, NOW), "lease-a", NOW);

        assertEquals(AgentOutboxClaim.Status.SKIPPED, claim.status());
        verify(dao).disposeDelivery(eq(fixture.delivery), eq("DEAD"), isNull(),
                eq(AgentOutboxRelayServiceImpl.UNADMITTED_PENDING), eq(NOW));
        verify(dao).disposeOutbox(eq(fixture.outbox), eq("DEAD"), isNull(), eq("NONE"),
                isNull(), isNull(), eq("NONE"), isNull(), isNull(), isNull(), isNull(),
                eq(AgentOutboxRelayServiceImpl.UNADMITTED_PENDING), eq(NOW));
        verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
    }

    @Test
    void dispatchStateOutsideExactScopeTerminalizesCaptureInsteadOfCreatingBacklog() throws Exception {
        Fixture fixture = pending();
        fixture.delivery.setTenantId("tenant-b");
        fixture.outbox.setTenantId("tenant-b");
        arrange(fixture);
        AgentOutboxRelayServiceImpl scoped = service(allowedGate(), ready());

        AgentOutboxClaim claim = scoped.claim(
                new AgentOutboxCandidate(1, "tenant-b", "client-a", 41, NOW, 0, "PENDING"),
                "lease-a", NOW);

        assertEquals(AgentOutboxClaim.Status.SKIPPED, claim.status());
        verify(dao).disposeDelivery(eq(fixture.delivery), eq("DEAD"), isNull(),
                eq(AgentCommandTransportWriterImpl.DISPATCH_SCOPE_MARKER), eq(NOW));
        verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
    }

    @Test
    void hashDestinationAndWireIdentityCorruptionAreDeadAndNeverClaimed() {
        for (Corruption corruption : Corruption.values()) {
            reset(dao);
            when(dao.disposeDelivery(any(), anyString(), any(), any(), anyLong())).thenReturn(1);
            when(dao.disposeOutbox(any(), anyString(), any(), anyString(), any(), any(),
                    anyString(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1);
            Fixture fixture = pending();
            String expected = switch (corruption) {
                case HASH -> { fixture.outbox.setWirePayloadHash(new byte[32]); yield "OUTBOX_STORED_HASH_CORRUPT"; }
                case DESTINATION -> { fixture.outbox.setDestination("wrong.exchange"); yield "DESTINATION_POLICY_REJECTED"; }
                case BODY -> {
                    byte[] drift = fixture.outbox.getWirePayload().clone();
                    drift[drift.length - 2] ^= 1;
                    fixture.outbox.setWirePayload(drift)
                            .setWirePayloadHash(AgentCommandAmqpContract.sha256(drift));
                    yield "WIRE_IDENTITY_DRIFT";
                }
            };
            arrange(fixture);

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service.claim(candidate(1, NOW), "lease-a", NOW).status(), corruption.name());
            verify(dao).disposeOutbox(eq(fixture.outbox), eq("DEAD"), isNull(),
                    eq("NONE"), isNull(), isNull(), eq("NONE"), isNull(), isNull(),
                    isNull(), isNull(), eq(expected), eq(NOW));
            verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
        }
    }

    @Test
    void dbIdentityStatusLeaseConfirmAndReturnCorruptionAreDeadBeforeClaim() {
        for (ClaimCorruption corruption : ClaimCorruption.values()) {
            reset(dao);
            when(dao.disposeDelivery(any(), anyString(), any(), any(), anyLong())).thenReturn(1);
            when(dao.disposeOutbox(any(), anyString(), any(), anyString(), any(), any(),
                    anyString(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1);
            Fixture fixture = switch (corruption) {
                case EVENT_ID -> pendingWith(outbox -> outbox.setEventId("evt-1 "));
                case ACTIVE_MESSAGE -> pendingWith(ignored -> { });
                case UNKNOWN_STATUS -> pendingWith(outbox -> outbox.setStatus("BROKEN"));
                case PENDING_RETRY_TIME -> pendingWith(ignored -> { });
                case RETRY_ACK_NOT_RETURNED -> retry();
                case RETRY_CONFIRM_ERROR -> retry();
                case STALE_RETURN_SHAPE -> staleClaim();
            };
            String expected = switch (corruption) {
                case EVENT_ID -> "SOURCE_IDENTITY_CORRUPT";
                case ACTIVE_MESSAGE -> {
                    fixture.delivery.setActiveMessageId("msg-other");
                    yield "SOURCE_IDENTITY_DRIFT";
                }
                case UNKNOWN_STATUS -> "OUTBOX_STATUS_CORRUPT";
                case PENDING_RETRY_TIME -> {
                    fixture.delivery.setNextRetryAt(NOW);
                    yield "PENDING_SHAPE_CORRUPT";
                }
                case RETRY_ACK_NOT_RETURNED -> {
                    fixture.outbox.setPublisherConfirmStatus("ACK")
                            .setConfirmedAt(NOW - 1)
                            .setConfirmError("RABBIT_NACK");
                    yield "RETRY_SHAPE_CORRUPT";
                }
                case RETRY_CONFIRM_ERROR -> {
                    fixture.outbox.setConfirmError("RABBIT_CONFIRM_TIMEOUT");
                    yield "RETRY_SHAPE_CORRUPT";
                }
                case STALE_RETURN_SHAPE -> {
                    fixture.outbox.setMandatoryReturnStatus("RETURNED");
                    yield "STALE_CLAIM_SHAPE_CORRUPT";
                }
            };
            arrange(fixture);

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service.claim(candidate(1, NOW), "lease-a", NOW).status(),
                    corruption.name());
            verify(dao).disposeOutbox(eq(fixture.outbox), eq("DEAD"), isNull(),
                    eq("NONE"), isNull(), isNull(), eq("NONE"), isNull(), isNull(),
                    isNull(), isNull(), eq(expected), eq(NOW));
            verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
        }
    }

    @Test
    void claimAtExpiryAndAttemptExhaustionReachFrozenTerminalStates() {
        Fixture expired = pending();
        expired.delivery.setExpiresAt(NOW);
        expired.outbox.setExpiresAt(NOW);
        expired.outbox.setWirePayload(wire(NOW));
        expired.outbox.setWirePayloadHash(AgentCommandAmqpContract.sha256(expired.outbox.getWirePayload()));
        arrange(expired);
        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service.claim(candidate(1, NOW), "lease", NOW).status());
        verify(dao).disposeOutbox(eq(expired.outbox), eq("EXPIRED"), isNull(),
                eq("NONE"), isNull(), isNull(), eq("NONE"), isNull(), isNull(), isNull(),
                isNull(), eq(AgentOutboxRelayServiceImpl.MESSAGE_EXPIRED), eq(NOW));
    }

    @Test
    void ackSettlesMatchingDeliveryAndOutboxTogetherInDeliveryFirstOrder() {
        Fixture claimed = claimed();
        arrange(claimed);
        AgentOutboxClaimToken token = token(claimed);

        AgentOutboxSettleResult settled = service.settle(token, AgentRabbitPublishResult.ack(), NOW);

        assertEquals(AgentOutboxSettleResult.PUBLISHED, settled);
        InOrder ordered = inOrder(dao);
        ordered.verify(dao).lockDelivery("tenant-a", "client-a", 41L);
        ordered.verify(dao).lockOutbox("tenant-a", "client-a", 1L);
        ordered.verify(dao).disposeDelivery(eq(claimed.delivery), eq("PUBLISHED"),
                isNull(), isNull(), eq(NOW));
        ordered.verify(dao).disposeOutbox(eq(claimed.outbox), eq("PUBLISHED"), isNull(),
                eq("ACK"), eq(NOW), isNull(), eq("NOT_RETURNED"), isNull(), isNull(),
                isNull(), eq(NOW), isNull(), eq(NOW));
    }

    @Test
    void oldCallbackCannotOverwriteNewAttemptAndAckOnlySettlesOldOutbox() {
        Fixture claimed = claimed();
        AgentOutboxClaimToken token = token(claimed);
        claimed.delivery.setActiveMessageId("msg-new").setActiveAttempt(2).setVersion(2L)
                .setLeaseOwner(null).setLeaseUntil(null);
        arrange(claimed);

        assertEquals(AgentOutboxSettleResult.PUBLISHED,
                service.settle(token, AgentRabbitPublishResult.ack(), NOW));
        verify(dao, never()).disposeDelivery(any(), anyString(), any(), any(), anyLong());
        verify(dao).disposeOutbox(eq(claimed.outbox), eq("PUBLISHED"), any(), anyString(),
                any(), any(), anyString(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void oldFailedCallbackDeadLettersOldOutboxWithoutRetryingOrRegressingDelivery() {
        Fixture claimed = claimed();
        AgentOutboxClaimToken token = token(claimed);
        claimed.delivery.setActiveMessageId("msg-new").setActiveAttempt(2).setVersion(2L)
                .setLeaseOwner(null).setLeaseUntil(null);
        arrange(claimed);

        assertEquals(AgentOutboxSettleResult.DEAD,
                service.settle(token, nack(), NOW));
        verify(dao, never()).disposeDelivery(any(), anyString(), any(), any(), anyLong());
        verify(dao).disposeOutbox(eq(claimed.outbox), eq("DEAD"), isNull(), eq("NACK"),
                eq(NOW), eq("RABBIT_NACK"), eq("NOT_RETURNED"), isNull(), isNull(),
                isNull(), isNull(), eq(AgentOutboxRelayServiceImpl.STALE_DELIVERY_FENCE), eq(NOW));
    }

    @Test
    void failureRetriesWithSameDeliveryTransportFenceAndDeterministicBoundedBackoff() {
        Fixture claimed = claimed();
        arrange(claimed);
        AgentOutboxClaimToken token = token(claimed);

        assertEquals(AgentOutboxSettleResult.RETRY, service.settle(token, nack(), NOW));

        long retryAt = settings().nextRetryAt("evt-1", 1, 1, NOW, EXPIRES);
        verify(dao).disposeDelivery(eq(claimed.delivery), eq("RETRY"), eq(retryAt),
                eq("RABBIT_NACK"), eq(NOW));
        verify(dao).disposeOutbox(eq(claimed.outbox), eq("RETRY"), eq(retryAt),
                eq("NACK"), eq(NOW), eq("RABBIT_NACK"), eq("NOT_RETURNED"), isNull(),
                isNull(), isNull(), isNull(), eq("RABBIT_NACK"), eq(NOW));
        assertEquals(1, token.deliveryActiveAttempt());
    }

    @Test
    void exhaustedFailureAndExpiryBoundaryNeverScheduleBeyondExpiry() {
        Fixture exhausted = claimed();
        exhausted.outbox.setAttemptCount(20).setActiveAttempt(1).setVersion(1L);
        arrange(exhausted);
        AgentOutboxClaimToken token = new AgentOutboxClaimToken(
                1, 41, "tenant-a", "client-a", "evt-1", "msg-1", "cmd-1",
                "task-1", "agent-1", "task.invite", exhausted.outbox.getDestination(),
                exhausted.outbox.getRoutingKey(), exhausted.outbox.getWirePayload(),
                exhausted.outbox.getWirePayloadHash(), EXPIRES, "lease-a", NOW + 30_000,
                20, 1, "PENDING", "msg-1", 1, 1);
        assertEquals(AgentOutboxSettleResult.FAILED, service.settle(token, nack(), NOW));
        verify(dao).disposeOutbox(eq(exhausted.outbox), eq("FAILED"), isNull(), anyString(),
                any(), any(), anyString(), any(), any(), any(), any(),
                eq(AgentOutboxRelayServiceImpl.ATTEMPT_EXHAUSTED), eq(NOW));

        long nearExpiry = settings().nextRetryAt("evt-1", 2, 1, EXPIRES - 1, EXPIRES);
        assertEquals(EXPIRES, nearExpiry);
        assertEquals(EXPIRES, settings().nextRetryAt("evt-1", 2, 1, EXPIRES, EXPIRES));
    }


    @Test
    void ackNotReturnedWinsAtAndAfterExpiryForActiveAndStaleDeliveryFences() {
        for (long settledAt : new long[] {EXPIRES, EXPIRES + 1}) {
            for (boolean active : new boolean[] {true, false}) {
                reset(dao);
                stubMutationSuccess();
                Fixture claimed = claimed();
                AgentOutboxClaimToken token = token(claimed);
                if (!active) {
                    claimed.delivery.setActiveMessageId("msg-new").setActiveAttempt(2)
                            .setVersion(2L).setLeaseOwner(null).setLeaseUntil(null);
                }
                arrange(claimed);

                assertEquals(AgentOutboxSettleResult.PUBLISHED,
                        service.settle(token, AgentRabbitPublishResult.ack(), settledAt));
                verify(dao).disposeOutbox(eq(claimed.outbox), eq("PUBLISHED"), isNull(),
                        eq("ACK"), eq(settledAt), isNull(), eq("NOT_RETURNED"), isNull(),
                        isNull(), isNull(), eq(settledAt), isNull(), eq(settledAt));
                if (active) {
                    verify(dao).disposeDelivery(eq(claimed.delivery), eq("PUBLISHED"),
                            isNull(), isNull(), eq(settledAt));
                } else {
                    verify(dao, never()).disposeDelivery(any(), anyString(), any(), any(), anyLong());
                }
            }
        }
    }

    @Test
    void discoveryQuarantinesBoundedPoisonLaneBeforeReturningLegalCandidate() {
        AgentOutboxCandidate badDeliveryId =
                new AgentOutboxCandidate(1, "tenant-a", "client-a", 0, 1, 0, "PENDING");
        AgentOutboxCandidate blankScope =
                new AgentOutboxCandidate(2, "", "client-a", 41, 2, 0, "PENDING");
        AgentOutboxCandidate controlScope =
                new AgentOutboxCandidate(3, "tenant-" + Character.toString(1), "client-a", 41, 3, 0, "PENDING");
        AgentOutboxCandidate missingDelivery =
                new AgentOutboxCandidate(4, "tenant-a", "client-a", 404, 4, 0, "PENDING");
        AgentOutboxCandidate legal =
                new AgentOutboxCandidate(10, "tenant-a", "client-a", 41, 10, 0, "PENDING");
        when(dao.selectCorruptCandidates(NOW, 1)).thenReturn(
                List.of(badDeliveryId), List.of(blankScope),
                List.of(controlScope), List.of(missingDelivery));
        when(dao.selectDueCandidates(NOW, 4)).thenReturn(List.of(legal));
        when(dao.selectStaleCandidates(NOW, 4)).thenReturn(List.of());
        when(dao.lockOutboxForQuarantine(badDeliveryId))
                .thenReturn(poisonOutbox(badDeliveryId));
        when(dao.lockOutboxForQuarantine(blankScope))
                .thenReturn(poisonOutbox(blankScope));
        when(dao.lockOutboxForQuarantine(controlScope))
                .thenReturn(poisonOutbox(controlScope));
        when(dao.lockOutboxForQuarantine(missingDelivery))
                .thenReturn(poisonOutbox(missingDelivery));

        for (int poll = 0; poll < 4; poll++) {
            assertEquals(List.of(10L), service.discover(NOW, 1).stream()
                    .map(AgentOutboxCandidate::outboxId).toList());
        }

        verify(dao, times(4)).selectCorruptCandidates(NOW, 1);
        verify(dao, times(4)).selectDueCandidates(NOW, 4);
        verify(dao, times(4)).selectStaleCandidates(NOW, 4);
        verify(dao).quarantineOutbox(any(), eq(AgentOutboxRelayServiceImpl.DISCOVERY_DELIVERY_ID_INVALID), eq(NOW));
        verify(dao, times(2)).quarantineOutbox(any(),
                eq(AgentOutboxRelayServiceImpl.DISCOVERY_SCOPE_INVALID), eq(NOW));
        verify(dao).quarantineOutbox(any(), eq(AgentOutboxRelayServiceImpl.DISCOVERY_DELIVERY_NOT_FOUND), eq(NOW));
        verify(dao, never()).quarantineDelivery(any(), anyString(), anyLong());
        verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
    }

    @Test
    void normalDiscoveryDefensivelyQuarantinesMaxMinusOneHint() {
        Fixture fixture = pending();
        fixture.outbox.setVersion(Long.MAX_VALUE - 1);
        AgentOutboxCandidate poison = new AgentOutboxCandidate(
                1, "tenant-a", "client-a", 41, NOW, Long.MAX_VALUE - 1, "PENDING");
        when(dao.selectCorruptCandidates(NOW, 1)).thenReturn(List.of());
        when(dao.selectDueCandidates(NOW, 4)).thenReturn(List.of(poison));
        when(dao.selectStaleCandidates(NOW, 4)).thenReturn(List.of());
        when(dao.lockDelivery("tenant-a", "client-a", 41L)).thenReturn(fixture.delivery);
        when(dao.lockOutboxForQuarantine(poison)).thenReturn(fixture.outbox);

        assertEquals(List.of(), service.discover(NOW, 1));

        verify(dao).quarantineDelivery(eq(fixture.delivery),
                eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
        verify(dao).quarantineOutbox(eq(fixture.outbox),
                eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
        verify(dao, never()).claimDelivery(any(), anyString(), anyLong(), any(), anyLong());
        verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
    }

    @Test
    void corruptionLaneQuarantinesMaxMinusOneBeforeNormalDiscovery() {
        Fixture fixture = pending();
        fixture.outbox.setVersion(Long.MAX_VALUE - 1);
        AgentOutboxCandidate poison = new AgentOutboxCandidate(
                1, "tenant-a", "client-a", 41, NOW, Long.MAX_VALUE - 1, "PENDING");
        when(dao.selectCorruptCandidates(NOW, 1)).thenReturn(List.of(poison));
        when(dao.selectDueCandidates(NOW, 4)).thenReturn(List.of());
        when(dao.selectStaleCandidates(NOW, 4)).thenReturn(List.of());
        when(dao.lockDelivery("tenant-a", "client-a", 41L)).thenReturn(fixture.delivery);
        when(dao.lockOutboxForQuarantine(poison)).thenReturn(fixture.outbox);

        assertEquals(List.of(), service.discover(NOW, 1));

        verify(dao).quarantineDelivery(eq(fixture.delivery),
                eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
        verify(dao).quarantineOutbox(eq(fixture.outbox),
                eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
        verify(dao, never()).claimDelivery(any(), anyString(), anyLong(), any(), anyLong());
        verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
    }

    @Test
    void maxMinusTwoLoserCannotQuarantineFreshWinner() {
        for (VersionMax max : VersionMax.values()) {
            reset(dao);
            stubMutationSuccess();
            Fixture winner = claimed();
            long candidateOutboxVersion = 0L;
            if (max != VersionMax.OUTBOX_ONLY) {
                winner.delivery.setVersion(Long.MAX_VALUE - 1);
            }
            if (max != VersionMax.DELIVERY_ONLY) {
                winner.outbox.setVersion(Long.MAX_VALUE - 1);
                candidateOutboxVersion = Long.MAX_VALUE - 2;
            }
            arrange(winner);
            AgentOutboxCandidate loserHint = new AgentOutboxCandidate(
                    1, "tenant-a", "client-a", 41, NOW,
                    candidateOutboxVersion, "PENDING");

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service.claim(loserHint, "lease-b", NOW).status(), max.name());

            assertEquals("CLAIMED", winner.outbox.getStatus());
            assertEquals("lease-a", winner.outbox.getLeaseOwner());
            verify(dao, never()).quarantineDelivery(any(), anyString(), anyLong());
            verify(dao, never()).quarantineOutbox(any(), anyString(), anyLong());
            verify(dao, never()).disposeDelivery(any(), anyString(), any(), any(), anyLong());
            verify(dao, never()).disposeOutbox(any(), anyString(), any(), anyString(), any(),
                    any(), anyString(), any(), any(), any(), any(), any(), anyLong());
            verify(dao, never()).claimDelivery(any(), anyString(), anyLong(), any(), anyLong());
            verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
        }
    }

    @Test
    void claimQuarantinesMaxMinusOneToReserveSettlementIncrement() {
        for (VersionMax max : VersionMax.values()) {
            reset(dao);
            stubMutationSuccess();
            Fixture fixture = pending();
            if (max != VersionMax.OUTBOX_ONLY) fixture.delivery.setVersion(Long.MAX_VALUE - 1);
            if (max != VersionMax.DELIVERY_ONLY) fixture.outbox.setVersion(Long.MAX_VALUE - 1);
            arrange(fixture);

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service.claim(candidate(1, NOW), "lease-a", NOW).status(), max.name());
            verify(dao).quarantineDelivery(eq(fixture.delivery),
                    eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
            verify(dao).quarantineOutbox(eq(fixture.outbox),
                    eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
            verify(dao, never()).claimDelivery(any(), anyString(), anyLong(), any(), anyLong());
            verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
        }
    }

    @Test
    void claimQuarantinesDeliveryOutboxVersionMaxWithoutOverflow() {
        for (VersionMax max : VersionMax.values()) {
            reset(dao);
            stubMutationSuccess();
            Fixture fixture = pending();
            if (max != VersionMax.OUTBOX_ONLY) fixture.delivery.setVersion(Long.MAX_VALUE);
            if (max != VersionMax.DELIVERY_ONLY) fixture.outbox.setVersion(Long.MAX_VALUE);
            arrange(fixture);

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service.claim(candidate(1, NOW), "lease-a", NOW).status(), max.name());
            verify(dao).quarantineDelivery(eq(fixture.delivery),
                    eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
            verify(dao).quarantineOutbox(eq(fixture.outbox),
                    eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
            verify(dao, never()).claimDelivery(any(), anyString(), anyLong(), any(), anyLong());
            verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
        }
    }

    @Test
    void settleQuarantinesActiveVersionMaxButStaleMaxDeliveryCannotPoisonOldAck() {
        for (VersionMax max : VersionMax.values()) {
            reset(dao);
            stubMutationSuccess();
            Fixture fixture = claimed();
            if (max != VersionMax.OUTBOX_ONLY) fixture.delivery.setVersion(Long.MAX_VALUE);
            if (max != VersionMax.DELIVERY_ONLY) fixture.outbox.setVersion(Long.MAX_VALUE);
            arrange(fixture);
            AgentOutboxClaimToken token = token(
                    fixture, fixture.outbox.getVersion(), fixture.delivery.getVersion());

            assertEquals(AgentOutboxSettleResult.DEAD,
                    service.settle(token, AgentRabbitPublishResult.ack(), NOW), max.name());
            verify(dao).quarantineDelivery(eq(fixture.delivery),
                    eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
            verify(dao).quarantineOutbox(eq(fixture.outbox),
                    eq(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED), eq(NOW));
        }

        reset(dao);
        stubMutationSuccess();
        Fixture stale = claimed();
        AgentOutboxClaimToken old = token(stale);
        stale.delivery.setActiveMessageId("msg-new").setActiveAttempt(2)
                .setVersion(Long.MAX_VALUE).setLeaseOwner(null).setLeaseUntil(null);
        arrange(stale);
        assertEquals(AgentOutboxSettleResult.PUBLISHED,
                service.settle(old, AgentRabbitPublishResult.ack(), NOW));
        verify(dao, never()).quarantineDelivery(any(), anyString(), anyLong());
        verify(dao, never()).quarantineOutbox(any(), anyString(), anyLong());
        verify(dao).disposeOutbox(eq(stale.outbox), eq("PUBLISHED"), any(), anyString(),
                any(), any(), anyString(), any(), any(), any(), any(), any(), anyLong());
    }


    @Test
    void staleCallbackCannotMutateMaxOrMaxMinusOneVersionRows() {
        for (long fencedVersion : new long[] {Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            Fixture fixture = claimed();
            AgentOutboxClaimToken old = token(fixture);
            fixture.delivery.setVersion(fencedVersion);
            fixture.outbox.setVersion(fencedVersion);
            arrange(fixture);

            assertEquals(AgentOutboxSettleResult.STALE,
                    service.settle(old, AgentRabbitPublishResult.ack(), NOW));
        }
        verify(dao, never()).quarantineDelivery(any(), anyString(), anyLong());
        verify(dao, never()).quarantineOutbox(any(), anyString(), anyLong());
        verify(dao, never()).disposeDelivery(any(), anyString(), any(), any(), anyLong());
        verify(dao, never()).disposeOutbox(any(), anyString(), any(), anyString(), any(), any(),
                anyString(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void staleTokenIsExplicitAndPerformsNoDisposition() {
        Fixture claimed = claimed();
        claimed.outbox.setVersion(2L);
        arrange(claimed);

        AgentOutboxClaimToken stale = new AgentOutboxClaimToken(
                1, 41, "tenant-a", "client-a", "evt-1", "msg-1", "cmd-1",
                "task-1", "agent-1", "task.invite", claimed.outbox.getDestination(),
                claimed.outbox.getRoutingKey(), claimed.outbox.getWirePayload(),
                claimed.outbox.getWirePayloadHash(), EXPIRES, "lease-a", NOW + 30_000,
                1, 1, "PENDING", "msg-1", 1, 1);
        assertEquals(AgentOutboxSettleResult.STALE,
                service.settle(stale, AgentRabbitPublishResult.ack(), NOW));
        verify(dao, never()).disposeDelivery(any(), anyString(), any(), any(), anyLong());
        verify(dao, never()).disposeOutbox(any(), anyString(), any(), anyString(), any(), any(),
                anyString(), any(), any(), any(), any(), any(), anyLong());
    }

    private AgentOutboxRelayServiceImpl service(
            AgentRabbitSafetyGate gate, AgentRabbitTopologyReadiness readiness) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new AgentOutboxRelayServiceImpl(
                dao, gate, MANIFEST, readiness, settings(), transactions);
    }

    private static AgentOutboxRelaySettings settings() {
        return new AgentOutboxRelaySettings(new AgentRabbitSafetyProperties.RabbitPublish(true));
    }

    private void stubMutationSuccess() {
        when(dao.claimDelivery(any(), anyString(), anyLong(), isNull(), anyLong())).thenReturn(1);
        when(dao.claimOutbox(any(), anyString(), anyLong(), isNull(), anyLong())).thenReturn(1);
        when(dao.disposeDelivery(any(), anyString(), any(), any(), anyLong())).thenReturn(1);
        when(dao.disposeOutbox(any(), anyString(), any(), anyString(), any(), any(),
                anyString(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1);
        when(dao.quarantineDelivery(any(), anyString(), anyLong())).thenReturn(1);
        when(dao.quarantineOutbox(any(), anyString(), anyLong())).thenReturn(1);
    }

    private void verifyNoRelayMutation() {
        verify(dao, never()).claimDelivery(any(), anyString(), anyLong(), any(), anyLong());
        verify(dao, never()).claimOutbox(any(), anyString(), anyLong(), any(), anyLong());
        verify(dao, never()).disposeDelivery(any(), anyString(), any(), any(), anyLong());
        verify(dao, never()).disposeOutbox(any(), anyString(), any(), anyString(), any(), any(),
                anyString(), any(), any(), any(), any(), any(), anyLong());
        verify(dao, never()).quarantineDelivery(any(), anyString(), anyLong());
        verify(dao, never()).quarantineOutbox(any(), anyString(), anyLong());
    }

    private static AgentOutboxEventEntity poisonOutbox(AgentOutboxCandidate candidate) {
        AgentOutboxEventEntity outbox = pending().outbox;
        outbox.setId(candidate.outboxId());
        outbox.setTenantId(candidate.tenantId());
        outbox.setClientId(candidate.clientId());
        outbox.setDeliveryId(candidate.deliveryId()).setVersion(candidate.outboxVersion())
                .setStatus(candidate.outboxStatus()).setNextRetryAt(null);
        return outbox;
    }

    private void arrange(Fixture fixture) {
        when(dao.lockDelivery(fixture.delivery.getTenantId(), fixture.delivery.getClientId(), 41L))
                .thenReturn(fixture.delivery);
        when(dao.lockOutbox(fixture.outbox.getTenantId(), fixture.outbox.getClientId(), 1L))
                .thenReturn(fixture.outbox);
    }

    private static Fixture pending() {
        byte[] command = "business".getBytes(StandardCharsets.UTF_8);
        byte[] wire = wire(EXPIRES);
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(41L).setCommandId("cmd-1").setTaskId("task-1")
                .setTargetAgentId("agent-1").setCommandType("task.invite")
                .setCommandPayload(command).setCommandPayloadHash(AgentCommandAmqpContract.sha256(command))
                .setStatus("PENDING").setAttemptCount(1).setNextRetryAt(null)
                .setLeaseOwner(null).setLeaseUntil(null).setActiveMessageId("msg-1")
                .setActiveAttempt(1).setExpiresAt(EXPIRES)
                .setLastError(AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER)
                .setVersion(0L);
        delivery.setTenantId("tenant-a"); delivery.setClientId("client-a");
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setId(1L).setEventId("evt-1").setMessageId("msg-1")
                .setCommandId("cmd-1").setDeliveryId(41L).setAggregateType("task")
                .setAggregateId("task-1").setDestination(MANIFEST.defaultCommandPublishRoute().destination())
                .setRoutingKey(MANIFEST.defaultCommandPublishRoute().routingKey())
                .setWirePayload(wire).setWirePayloadHash(AgentCommandAmqpContract.sha256(wire))
                .setStatus("PENDING").setAttemptCount(0).setNextRetryAt(null)
                .setLeaseOwner(null).setLeaseUntil(null).setActiveAttempt(1).setExpiresAt(EXPIRES)
                .setPublisherConfirmStatus("NONE").setMandatoryReturnStatus("NONE")
                .setLastError(AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER)
                .setVersion(0L);
        outbox.setTenantId("tenant-a"); outbox.setClientId("client-a");
        return new Fixture(delivery, outbox);
    }

    private static Fixture automaticReplay(
            String requester, String approver, String reason) {
        Fixture fixture = pending();
        fixture.delivery.setActiveMessageId("msg-2")
                .setAttemptCount(2).setActiveAttempt(2)
                .setReplayParentMessageId("msg-1")
                .setReplayRequesterId(requester)
                .setReplayApproverId(approver)
                .setReplayReason(reason);
        fixture.outbox.setMessageId("msg-2").setActiveAttempt(2)
                .setReplayParentMessageId("msg-1")
                .setReplayRequesterId(requester)
                .setReplayApproverId(approver)
                .setReplayReason(reason);
        byte[] rewritten = new String(wire(EXPIRES), StandardCharsets.UTF_8)
                .replace("\"messageId\":\"msg-1\"",
                        "\"messageId\":\"msg-2\"")
                .replace("\"attempt\":1", "\"attempt\":2")
                .getBytes(StandardCharsets.UTF_8);
        fixture.outbox.setWirePayload(rewritten)
                .setWirePayloadHash(AgentCommandAmqpContract.sha256(rewritten));
        return fixture;
    }

    private static Fixture pendingWith(
            java.util.function.Consumer<AgentOutboxEventEntity> mutation) {
        Fixture fixture = pending();
        mutation.accept(fixture.outbox);
        return fixture;
    }

    private static Fixture retry() {
        Fixture fixture = pending();
        fixture.delivery.setStatus("RETRY").setNextRetryAt(NOW)
                .setLeaseOwner(null).setLeaseUntil(null)
                .setLastError("RABBIT_NACK").setVersion(2L);
        fixture.outbox.setStatus("RETRY").setAttemptCount(1).setActiveAttempt(1)
                .setNextRetryAt(NOW).setLeaseOwner(null).setLeaseUntil(null)
                .setPublisherConfirmStatus("NACK").setConfirmedAt(NOW - 1)
                .setConfirmError("RABBIT_NACK")
                .setMandatoryReturnStatus("NOT_RETURNED")
                .setLastError("RABBIT_NACK").setVersion(2L);
        return fixture;
    }

    private static Fixture staleClaim() {
        Fixture fixture = claimed();
        fixture.delivery.setLeaseUntil(NOW);
        fixture.outbox.setLeaseUntil(NOW);
        return fixture;
    }

    private static Fixture claimed() {
        Fixture fixture = pending();
        fixture.delivery.setLeaseOwner("lease-a").setLeaseUntil(NOW + 30_000)
                .setLastError(null).setVersion(1L);
        fixture.outbox.setStatus("CLAIMED").setAttemptCount(1).setActiveAttempt(1)
                .setLeaseOwner("lease-a").setLeaseUntil(NOW + 30_000)
                .setPublisherConfirmStatus("PENDING").setMandatoryReturnStatus("PENDING")
                .setLastError(null).setVersion(1L);
        return fixture;
    }

    private static AgentOutboxClaimToken token(Fixture fixture) {
        return new AgentOutboxClaimToken(1, 41, "tenant-a", "client-a", "evt-1", "msg-1",
                "cmd-1", "task-1", "agent-1", "task.invite",
                fixture.outbox.getDestination(), fixture.outbox.getRoutingKey(),
                fixture.outbox.getWirePayload(), fixture.outbox.getWirePayloadHash(), EXPIRES,
                "lease-a", NOW + 30_000, 1, 1, "PENDING", "msg-1", 1, 1);
    }

    private static AgentOutboxClaimToken token(
            Fixture fixture, long outboxVersion, long deliveryVersion) {
        return new AgentOutboxClaimToken(1, 41, "tenant-a", "client-a", "evt-1", "msg-1",
                "cmd-1", "task-1", "agent-1", "task.invite",
                fixture.outbox.getDestination(), fixture.outbox.getRoutingKey(),
                fixture.outbox.getWirePayload(), fixture.outbox.getWirePayloadHash(), EXPIRES,
                "lease-a", NOW + 30_000, 1, outboxVersion,
                "PENDING", "msg-1", 1, deliveryVersion);
    }

    private static byte[] wire(long expiresAt) {
        return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\"," +
                "\"tenantId\":\"tenant-a\",\"clientId\":\"client-a\"," +
                "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\"," +
                "\"commandType\":\"task.invite\",\"attempt\":1," +
                "\"expiresAt\":" + expiresAt + ",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static AgentRabbitPublishResult nack() {
        return new AgentRabbitPublishResult(AgentRabbitPublishResult.Type.NACK,
                "NACK", "NOT_RETURNED", null, null, "RABBIT_NACK");
    }

    private static AgentOutboxCandidate candidate(long id, long eligible) {
        return new AgentOutboxCandidate(id, "tenant-a", "client-a", 41, eligible, 0, "PENDING");
    }

    private static AgentRabbitTopologyReadiness ready() throws Exception {
        AgentRabbitTopologyReadiness readiness = notReady();
        Method mark = AgentRabbitTopologyReadiness.class.getDeclaredMethod("markProvisioned");
        mark.setAccessible(true); mark.invoke(readiness);
        return readiness;
    }

    private static AgentRabbitTopologyReadiness notReady() {
        return new AgentRabbitTopologyConfiguration().agentRabbitTopologyReadiness(MANIFEST);
    }

    private static AgentRabbitSafetyGate allowedGate() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated"));
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope("tenant-a", "client-a"))));
    }

    private static AgentRabbitSafetyGate mqShadowGate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(false),
                new AgentRabbitSafetyProperties.RabbitDispatch(false),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated")));
    }

    private record Fixture(AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) { }
    private enum Corruption { HASH, DESTINATION, BODY }
    private enum VersionMax { DELIVERY_ONLY, OUTBOX_ONLY, BOTH }
    private enum ClaimCorruption {
        EVENT_ID, ACTIVE_MESSAGE, UNKNOWN_STATUS, PENDING_RETRY_TIME,
        RETRY_ACK_NOT_RETURNED, RETRY_CONFIRM_ERROR, STALE_RETURN_SHAPE
    }
}
