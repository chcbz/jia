package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseScanDTO;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentWorkItemLeaseServiceImplTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER_AGENT = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TOKEN = "lease_test_token";
    private static final long NOW = 1_000L;
    private static final long MAX_DURATION = 500L;

    @Mock
    AgentTaskMemberDao memberDao;
    @Mock
    AgentTaskWorkItemDao workItemDao;

    @Mock
    AgentTaskMutationTransaction mutationTransaction;
    @Mock
    AgentTaskEventWriter eventWriter;

    AgentWorkItemLeaseServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(mutationTransaction.executeWithLockedTaskRoot(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
                    return mutation.apply(new cn.jia.agent.entity.AgentTaskMetaEntity());
                });
        service = new AgentWorkItemLeaseServiceImpl(
                memberDao, workItemDao, mutationTransaction, eventWriter,
                () -> NOW, () -> TOKEN, MAX_DURATION);
        lenient().when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, AGENT))
                .thenReturn(member(AGENT, "accepted"));
    }

    @Test
    void claimReadyUsesScopedCasAndPreservesCompleteSnapshot() {
        AgentTaskWorkItemEntity current = item("ready", 3L, null, null, null, 1, 3);
        current.setDescription("preserve-description");
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(current);
        when(workItemDao.claimReadyByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(null), eq(3L), any()))
                .thenReturn(1);

        AgentWorkItemLeaseDTO result = service.claim(
                TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 3L, 200L));

        assertEquals("claimed", result.getStatus());
        assertEquals(TOKEN, result.getLeaseToken());
        assertEquals(1_200L, result.getLeaseUntil());
        assertEquals(1, result.getAttemptCount());
        assertEquals(4L, result.getVersion());
        ArgumentCaptor<AgentTaskWorkItemDTO> update = ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao).claimReadyByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(null), eq(3L), update.capture());
        assertEquals("preserve-description", update.getValue().getDescription());
        assertEquals(AGENT, update.getValue().getAssigneeAgentId());
        assertEquals(1, update.getValue().getAttemptCount(), "claim does not consume an attempt");
    }

    @Test
    void expiredHostingDeniesNewClaimBeforeCasButDoesNotKillRunningWork() {
        var gate = org.mockito.Mockito.mock(cn.jia.agent.service.AgentHostingWorkAdmission.class);
        service.setHostingWorkAdmission(gate);
        org.mockito.Mockito.doThrow(new cn.jia.agent.hosting.HostingRentApplicationException(409,
                "HOSTING_RENT_RENEWAL_REQUIRED")).when(gate).requireNewWork(TENANT, CLIENT, AGENT);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("ready", 3L, null, null, null, 0, 3));
        assertThrows(cn.jia.agent.hosting.HostingRentApplicationException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 3L, 200L)));
        verify(workItemDao, never()).claimReadyByVersion(any(), any(), any(), any(), any(), anyLong(), any());
        verifyNoInteractions(eventWriter);
        org.mockito.Mockito.clearInvocations(gate);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 5L, AGENT, TOKEN, 1_300L, 0, 3));
        assertEquals(1_300L, service.heartbeat(TENANT, CLIENT, TASK, WORK,
                heartbeatCommand(AGENT, TOKEN, 5L, 100L)).getLeaseUntil());
        verifyNoInteractions(gate); // Existing execution is not killed by rental expiry.
    }

    @Test
    void orchestratorAssignedReadyItemCanOnlyBeClaimedByItsTarget() {
        AgentTaskWorkItemEntity current = item("ready", 3L, AGENT, null, null, 0, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(current);
        when(workItemDao.claimReadyByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(3L), any()))
                .thenReturn(1);

        AgentWorkItemLeaseDTO result = service.claim(
                TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 3L, 200L));

        assertEquals("claimed", result.getStatus());
        verify(workItemDao).claimReadyByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(3L), any());
    }

    @Test
    void claimRejectsNonReadyAndAssignedToDifferentAgentWithoutCas() {
        AgentTaskWorkItemEntity running = item("running", 3L, AGENT, TOKEN, 1_200L, 0, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(running);
        AgentTaskStateException nonReady = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 3L, 200L)));
        assertEquals(Reason.INVALID_TRANSITION, nonReady.getReason());

        AgentTaskWorkItemEntity reserved = item("ready", 3L, OTHER_AGENT, null, null, 0, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(reserved);
        AgentTaskStateException mismatched = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 3L, 200L)));
        assertEquals(Reason.LEASE_INVALID, mismatched.getReason());
        verify(workItemDao, never()).claimReadyByVersion(
                any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void claimRejectsInvalidDurationAndCanonicalAgentBeforeReads() {
        AgentTaskStateException duration = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 0L, 501L)));
        assertEquals(Reason.INVALID_REQUEST, duration.getReason());

        AgentTaskStateException agent = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK,
                        claimCommand("runtime-agent", 0L, 100L)));
        assertEquals(Reason.INVALID_REQUEST, agent.getReason());
        verifyNoInteractions(workItemDao);
    }

    @Test
    void claimRejectsNoncanonicalPersistedStatusAndIllegalAttempts() {
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item(" READY ", 0L, null, null, null, 0, 3));
        AgentTaskStateException status = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 0L, 100L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, status.getReason());

        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("ready", 0L, null, null, null, 3, 3));
        AgentTaskStateException attempts = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 0L, 100L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, attempts.getReason());
    }

    @Test
    void claimRejectsReadyRecordWithPersistedLeaseState() {
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("ready", 0L, null, "stale-token", 1_200L, 0, 3));
        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 0L, 100L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        verify(workItemDao, never()).claimReadyByVersion(
                any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void claimRequiresActiveCanonicalMember() {
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, AGENT))
                .thenReturn(member(AGENT, "blocked"));
        AgentTaskStateException inactive = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 0L, 100L)));
        assertEquals(Reason.LEASE_INVALID, inactive.getReason());

        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, AGENT))
                .thenReturn(member(AGENT, " WORKING "));
        AgentTaskStateException noncanonical = assertThrows(AgentTaskStateException.class,
                () -> service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 0L, 100L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, noncanonical.getReason());
    }

    @Test
    void startRequiresExactActiveClaimAndChangesOnlyStatus() {
        AgentTaskWorkItemEntity current = item("claimed", 4L, AGENT, TOKEN, 1_200L, 0, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(current);
        when(workItemDao.updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("claimed"), eq(1_200L), eq(4L), eq(NOW), any())).thenReturn(1);

        AgentWorkItemLeaseDTO result = service.start(
                TENANT, CLIENT, TASK, WORK, actionCommand(AGENT, TOKEN, 4L));

        assertEquals("running", result.getStatus());
        assertEquals(TOKEN, result.getLeaseToken());
        assertEquals(1_200L, result.getLeaseUntil());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals("WORK_ITEM_STARTED", event.getValue().getEventType());
        assertFalse(event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void heartbeatExtendsWithoutShrinkingAndHonorsConfiguredLimit() {
        AgentTaskWorkItemEntity current = item("running", 5L, AGENT, TOKEN, 1_300L, 0, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(current);
        AgentWorkItemLeaseDTO noShrink = service.heartbeat(
                TENANT, CLIENT, TASK, WORK, heartbeatCommand(AGENT, TOKEN, 5L, 100L));
        assertEquals(1_300L, noShrink.getLeaseUntil());

        AgentTaskStateException tooLong = assertThrows(AgentTaskStateException.class,
                () -> service.heartbeat(
                        TENANT, CLIENT, TASK, WORK,
                        heartbeatCommand(AGENT, TOKEN, 5L, 501L)));
        assertEquals(Reason.INVALID_REQUEST, tooLong.getReason());
    }

    @Test
    void heartbeatRejectsExpiredOldTokenAndWrongStatusWithoutWrite() {
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 5L, AGENT, TOKEN, NOW, 0, 3));
        AgentTaskStateException expired = assertThrows(AgentTaskStateException.class,
                () -> service.heartbeat(
                        TENANT, CLIENT, TASK, WORK,
                        heartbeatCommand(AGENT, TOKEN, 5L, 100L)));
        assertEquals(Reason.LEASE_INVALID, expired.getReason());

        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 5L, AGENT, TOKEN, 1_200L, 0, 3));
        AgentTaskStateException token = assertThrows(AgentTaskStateException.class,
                () -> service.heartbeat(
                        TENANT, CLIENT, TASK, WORK,
                        heartbeatCommand(AGENT, "old-token", 5L, 100L)));
        assertEquals(Reason.LEASE_INVALID, token.getReason());

        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("submitted", 5L, AGENT, TOKEN, 1_200L, 0, 3));
        AgentTaskStateException state = assertThrows(AgentTaskStateException.class,
                () -> service.heartbeat(
                        TENANT, CLIENT, TASK, WORK,
                        heartbeatCommand(AGENT, TOKEN, 5L, 100L)));
        assertEquals(Reason.LEASE_INVALID, state.getReason());
        verify(workItemDao, never()).updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void releaseConsumesAttemptRequeuesAndClearsLease() {
        AgentTaskWorkItemEntity current = item("running", 5L, AGENT, TOKEN, 1_200L, 1, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(current);
        when(workItemDao.updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any())).thenReturn(1);

        AgentWorkItemLeaseDTO result = service.release(
                TENANT, CLIENT, TASK, WORK, actionCommand(AGENT, TOKEN, 5L));

        assertEquals("ready", result.getStatus());
        assertEquals(2, result.getAttemptCount());
        assertNull(result.getLeaseToken());
        assertNull(result.getLeaseUntil());
    }

    @Test
    void releaseAtMaxAttemptsFailsAndStillAppendsOnlyReleaseEvent() {
        AgentTaskWorkItemEntity current = item("claimed", 5L, AGENT, TOKEN, 1_200L, 2, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(current);
        when(workItemDao.updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any())).thenReturn(1);

        AgentWorkItemLeaseDTO result = service.release(
                TENANT, CLIENT, TASK, WORK, actionCommand(AGENT, TOKEN, 5L));

        assertEquals("failed", result.getStatus());
        assertEquals(3, result.getAttemptCount());
        assertNull(result.getLeaseToken());
        assertNull(result.getLeaseUntil());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals("WORK_ITEM_LEASE_RELEASED", event.getValue().getEventType());
        assertTrue(event.getValue().getEventJson().contains("\"toStatus\":\"failed\""));
        assertFalse(event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void cancelRequiresCurrentLeaseAndDoesNotConsumeAttempt() {
        AgentTaskWorkItemEntity current = item("claimed", 5L, AGENT, TOKEN, 1_200L, 1, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(current);
        when(workItemDao.updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any())).thenReturn(1);

        AgentWorkItemLeaseDTO result = service.cancel(
                TENANT, CLIENT, TASK, WORK, actionCommand(AGENT, TOKEN, 5L));

        assertEquals("cancelled", result.getStatus());
        assertEquals(1, result.getAttemptCount());
        assertNull(result.getLeaseToken());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals("WORK_ITEM_CANCELLED", event.getValue().getEventType());
        assertFalse(event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void validateLeaseForResultAllowsOnlyCurrentRunningLeaseAndNeverWrites() {
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 7L, AGENT, TOKEN, 1_200L, 0, 3));
        AgentWorkItemLeaseDTO valid = service.validateLeaseForResult(
                TENANT, CLIENT, TASK, WORK, actionCommand(AGENT, TOKEN, 7L));
        assertEquals(7L, valid.getVersion());

        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 7L, AGENT, "new-token", 1_200L, 0, 3));
        AgentTaskStateException late = assertThrows(AgentTaskStateException.class,
                () -> service.validateLeaseForResult(
                        TENANT, CLIENT, TASK, WORK, actionCommand(AGENT, TOKEN, 7L)));
        assertEquals(Reason.LEASE_INVALID, late.getReason());
        verify(workItemDao, never()).updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void crossScopeLookupReturnsGenericNotFoundWithoutCrossTenantProbe() {
        when(memberDao.findByTaskAndAgent("tenant-b", CLIENT, TASK, AGENT)).thenReturn(null);
        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> service.claim(
                        "tenant-b", CLIENT, TASK, WORK, claimCommand(AGENT, 0L, 100L)));
        assertEquals(Reason.NOT_FOUND, error.getReason());
        verify(memberDao).findByTaskAndAgent("tenant-b", CLIENT, TASK, AGENT);
        verifyNoInteractions(workItemDao);
    }

    @Test
    void activeLeaseRejectsInvalidMaxAttemptsAndLeaseHorizon() {
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 5L, AGENT, TOKEN, 1_200L, 0, 0));
        AgentTaskStateException maxAttempts = assertThrows(AgentTaskStateException.class,
                () -> service.heartbeat(
                        TENANT, CLIENT, TASK, WORK,
                        heartbeatCommand(AGENT, TOKEN, 5L, 100L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, maxAttempts.getReason());

        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 5L, AGENT, TOKEN, 1_501L, 0, 3));
        AgentTaskStateException horizon = assertThrows(AgentTaskStateException.class,
                () -> service.heartbeat(
                        TENANT, CLIENT, TASK, WORK,
                        heartbeatCommand(AGENT, TOKEN, 5L, 100L)));
        assertEquals(Reason.INVALID_PERSISTED_STATE, horizon.getReason());
        verify(workItemDao, never()).updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void expiryScannerRequeuesOrFailsWithExactCasAndCountsConflict() {
        AgentTaskWorkItemEntity requeue = item("claimed", 4L, AGENT, TOKEN, 900L, 0, 3);
        AgentTaskWorkItemEntity fail = item("running", 6L, OTHER_AGENT, "lease_other", 950L, 2, 3);
        fail.setWorkItemId("work-2");
        when(workItemDao.listExpiredLeases(TENANT, CLIENT, NOW, 10))
                .thenReturn(List.of(requeue, fail));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(requeue);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-2"))
                .thenReturn(fail);
        when(workItemDao.expireLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("claimed"), eq(900L), eq(4L), eq(NOW), any())).thenReturn(1);
        when(workItemDao.expireLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("work-2"), eq(OTHER_AGENT), eq("lease_other"),
                eq("running"), eq(950L), eq(6L), eq(NOW), any())).thenReturn(0);

        AgentWorkItemLeaseScanDTO scan = service.expireLeases(TENANT, CLIENT, 10);

        assertEquals(2, scan.getScannedCount());
        assertEquals(1, scan.getExpiredCount());
        assertEquals(1, scan.getRequeuedCount());
        assertEquals(0, scan.getFailedCount());
        assertEquals(1, scan.getConflictCount());
        assertEquals("ready", scan.getTransitions().get(0).getStatus());
        assertEquals(1, scan.getTransitions().get(0).getAttemptCount());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals("WORK_ITEM_REQUEUED", event.getValue().getEventType());
        assertFalse(event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void expiryScannerRejectsIllegalPersistedLeaseTimeAndAttempts() {
        AgentTaskWorkItemEntity invalidTime = item("claimed", 4L, AGENT, TOKEN, 0L, 0, 3);
        when(workItemDao.listExpiredLeases(TENANT, CLIENT, NOW, 10))
                .thenReturn(List.of(invalidTime));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(invalidTime);
        AgentTaskStateException time = assertThrows(AgentTaskStateException.class,
                () -> service.expireLeases(TENANT, CLIENT, 10));
        assertEquals(Reason.INVALID_PERSISTED_STATE, time.getReason());

        AgentTaskWorkItemEntity invalidAttempts = item("claimed", 4L, AGENT, TOKEN, 900L, -1, 3);
        when(workItemDao.listExpiredLeases(TENANT, CLIENT, NOW, 10))
                .thenReturn(List.of(invalidAttempts));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(invalidAttempts);
        AgentTaskStateException attempts = assertThrows(AgentTaskStateException.class,
                () -> service.expireLeases(TENANT, CLIENT, 10));
        assertEquals(Reason.INVALID_PERSISTED_STATE, attempts.getReason());
        verify(workItemDao, never()).expireLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void claimAndHeartbeatAppendCanonicalEventsWithoutLeaseToken() {
        AgentTaskWorkItemEntity ready = item("ready", 3L, null, null, null, 1, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(ready);
        when(workItemDao.claimReadyByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(null), eq(3L), any()))
                .thenReturn(1);
        service.claim(TENANT, CLIENT, TASK, WORK, claimCommand(AGENT, 3L, 200L));

        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> claimEvent =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(claimEvent.capture());
        assertEquals("WORK_ITEM_CLAIMED", claimEvent.getValue().getEventType());
        assertFalse(claimEvent.getValue().getEventJson().contains(TOKEN));
        assertTrue(claimEvent.getValue().getEventJson().contains("leaseExpiresAt"));
    }

    @Test
    void heartbeatNoShrinkIsNoOpWithZeroCasAndZeroEvent() {
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 5L, AGENT, TOKEN, 1_300L, 0, 3));

        AgentWorkItemLeaseDTO result = service.heartbeat(
                TENANT, CLIENT, TASK, WORK,
                heartbeatCommand(AGENT, TOKEN, 5L, 100L));

        assertEquals(5L, result.getVersion());
        verify(workItemDao, never()).updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void releaseAppendsOnlyLeaseReleasedToReadyAndCancelAppendsCancelled() {
        AgentTaskWorkItemEntity running = item("running", 5L, AGENT, TOKEN, 1_200L, 1, 3);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK)).thenReturn(running);
        when(workItemDao.updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        service.release(TENANT, CLIENT, TASK, WORK, actionCommand(AGENT, TOKEN, 5L));
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals("WORK_ITEM_LEASE_RELEASED", event.getValue().getEventType());
        assertTrue(event.getValue().getEventJson().contains("\"toStatus\":\"ready\""));
        assertTrue(event.getValue().getEventJson().contains("\"attemptCount\":2"));
        assertFalse(event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void heartbeatRenewalAppendsRenewedEventWithoutToken() {
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK))
                .thenReturn(item("running", 5L, AGENT, TOKEN, 1_100L, 0, 3));
        when(workItemDao.updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(1_100L), eq(5L), eq(NOW), any())).thenReturn(1);

        service.heartbeat(TENANT, CLIENT, TASK, WORK,
                heartbeatCommand(AGENT, TOKEN, 5L, 300L));

        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals("WORK_ITEM_LEASE_RENEWED", event.getValue().getEventType());
        assertTrue(event.getValue().getEventJson().contains("previousLeaseExpiresAt"));
        assertFalse(event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void expiryAtAttemptLimitAppendsOnlyFailedEvent() {
        AgentTaskWorkItemEntity failed = item("running", 6L, AGENT, TOKEN, 900L, 2, 3);
        when(workItemDao.listExpiredLeases(TENANT, CLIENT, NOW, 10))
                .thenReturn(List.of(failed));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(failed);
        when(workItemDao.expireLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(AGENT), eq(TOKEN),
                eq("running"), eq(900L), eq(6L), eq(NOW), any())).thenReturn(1);

        AgentWorkItemLeaseScanDTO scan = service.expireLeases(TENANT, CLIENT, 10);

        assertEquals(1, scan.getFailedCount());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals("WORK_ITEM_FAILED", event.getValue().getEventType());
        assertFalse(event.getValue().getEventJson().contains(TOKEN));
    }

    @Test
    void expiryPreservesDeterministicCandidateOrderAcrossTaskRootMutations() {
        AgentTaskWorkItemEntity first = item("claimed", 4L, AGENT, TOKEN, 800L, 0, 3);
        first.setWorkItemId("work-a");
        AgentTaskWorkItemEntity second = item("running", 6L, OTHER_AGENT, "lease_other", 900L, 2, 3);
        second.setWorkItemId("work-b");
        when(workItemDao.listExpiredLeases(TENANT, CLIENT, NOW, 10))
                .thenReturn(List.of(first, second));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-a"))
                .thenReturn(first);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-b"))
                .thenReturn(second);
        when(workItemDao.expireLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("work-a"), eq(AGENT), eq(TOKEN),
                eq("claimed"), eq(800L), eq(4L), eq(NOW), any())).thenReturn(1);
        when(workItemDao.expireLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq("work-b"), eq(OTHER_AGENT), eq("lease_other"),
                eq("running"), eq(900L), eq(6L), eq(NOW), any())).thenReturn(1);

        AgentWorkItemLeaseScanDTO scan = service.expireLeases(TENANT, CLIENT, 10);

        assertEquals(List.of("work-a", "work-b"), scan.getTransitions().stream()
                .map(AgentWorkItemLeaseDTO::getWorkItemId).toList());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskEventWriteCommand.class);
        verify(eventWriter, org.mockito.Mockito.times(2)).append(events.capture());
        assertEquals(List.of("WORK_ITEM_REQUEUED", "WORK_ITEM_FAILED"),
                events.getAllValues().stream().map(
                        cn.jia.agent.entity.AgentTaskEventWriteCommand::getEventType).toList());
    }

    private AgentTaskMemberEntity member(String agentId, String status) {
        AgentTaskMemberEntity member = new AgentTaskMemberEntity();
        member.setTaskId(TASK);
        member.setAgentId(agentId);
        member.setMemberRole("worker");
        member.setMemberStatus(status);
        member.setAssignmentSource("manual");
        member.setVersion(0L);
        member.setTenantId(TENANT);
        member.setClientId(CLIENT);
        return member;
    }

    private AgentTaskWorkItemEntity item(
            String status, long version, String assignee, String token, Long leaseUntil,
            int attempts, int maxAttempts) {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity();
        item.setWorkItemId(WORK);
        item.setTaskId(TASK);
        item.setTitle("Implement B04");
        item.setDescription("full snapshot");
        item.setWorkType("implementation");
        item.setRequiredAbilities("[]");
        item.setAssigneeAgentId(assignee);
        item.setStatus(status);
        item.setPriority(10);
        item.setRequiredItem(true);
        item.setDependencyJson("[]");
        item.setLeaseToken(token);
        item.setLeaseUntil(leaseUntil);
        item.setAttemptCount(attempts);
        item.setMaxAttempts(maxAttempts);
        item.setVersion(version);
        item.setTenantId(TENANT);
        item.setClientId(CLIENT);
        return item;
    }

    private AgentWorkItemLeaseCommandDTO claimCommand(String agent, long version, long duration) {
        AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
        command.setAgentId(agent);
        command.setExpectedVersion(version);
        command.setLeaseDurationMillis(duration);
        return command;
    }

    private AgentWorkItemLeaseCommandDTO actionCommand(String agent, String token, long version) {
        AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
        command.setAgentId(agent);
        command.setLeaseToken(token);
        command.setExpectedVersion(version);
        return command;
    }

    private AgentWorkItemLeaseCommandDTO heartbeatCommand(
            String agent, String token, long version, long duration) {
        AgentWorkItemLeaseCommandDTO command = actionCommand(agent, token, version);
        command.setLeaseDurationMillis(duration);
        return command;
    }
}
