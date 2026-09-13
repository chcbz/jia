package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.AgentWorkItemReassignmentDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentEntity;
import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentRequestDTO;
import cn.jia.agent.exception.AgentWorkItemReassignmentException;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentWorkItemReassignmentServiceTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";
    private static final String PREVIOUS = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String COORDINATOR = "agt_cccccccccccccccccccccccccccccccc";
    private static final String TOKEN = "old-lease-token";
    private static final String NEW_TOKEN = "fresh-lease-token";
    private static final long NOW = 1_800_000_000_000L;
    private static final long VERSION = 4L;

    private AgentWorkItemReassignmentDao reassignmentDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentIdentityService identityService;
    private AgentService agentService;
    private AgentTaskEventWriter eventWriter;
    private AgentCommandTransportWriter commandWriter;
    private AgentWorkItemLeaseService leaseService;
    private AgentWorkItemReassignmentServiceImpl service;
    private AgentTaskMetaEntity root;
    private AgentTaskWorkItemEntity workItem;
    private AgentCommandDeliveryEntity source;
    private AtomicReference<AgentWorkItemReassignmentEntity> inserted;

    @BeforeEach
    void setUp() {
        reassignmentDao = mock(AgentWorkItemReassignmentDao.class);
        memberDao = mock(AgentTaskMemberDao.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        identityService = mock(AgentIdentityService.class);
        agentService = mock(AgentService.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        commandWriter = mock(AgentCommandTransportWriter.class);
        leaseService = mock(AgentWorkItemLeaseService.class);
        root = root();
        workItem = workItem();
        source = source("DEAD", Integer.MAX_VALUE);
        inserted = new AtomicReference<>();

        AgentTaskMutationTransaction transaction = new InlineTransaction(root);
        for (String agent : List.of(PREVIOUS, TARGET, COORDINATOR)) {
            when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, TASK, agent))
                    .thenReturn(member(agent));
            when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, agent))
                    .thenReturn(runtime(agent));
        }
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                eq(TENANT), eq(CLIENT), eq(TENANT), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        doNothing().when(agentService).requireHostingNewWork(TENANT, CLIENT, TARGET);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, WORK))
                .thenReturn(workItem);
        when(reassignmentDao.findSourceCommand(TENANT, CLIENT, source.getCommandId()))
                .thenReturn(source);
        when(workItemDao.reassignExpiredLeaseByVersion(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(1);
        AgentTaskEventEntity event = new AgentTaskEventEntity().setEventId("evt_reassigned");
        when(eventWriter.append(any())).thenReturn(new AgentTaskEventWriteResult().setEvent(event));
        when(commandWriter.writeAuthorizedHall(any(), eq(COORDINATOR)))
                .thenAnswer(invocation -> {
                    AgentCommandDraft draft = invocation.getArgument(0);
                    return new AgentCommandTransportWriteResult(
                            9, draft.commandId(), "msg-new", "outbox-new", false);
                });
        doAnswer(invocation -> {
            AgentWorkItemReassignmentEntity receipt = invocation.getArgument(0);
            receipt.setId(1L);
            inserted.set(receipt);
            return 1;
        }).when(reassignmentDao).insert(any());
        service = new AgentWorkItemReassignmentServiceImpl(
                reassignmentDao, memberDao, workItemDao, transaction,
                identityService, agentService, eventWriter, commandWriter, leaseService,
                () -> NOW, () -> NEW_TOKEN, 300_000);
    }

    @Test
    void expiredLeaseReassignsExplicitTargetAndIgnoresTransportExhaustion() {
        var result = service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));

        assertEquals(TARGET, result.getTargetAgentId());
        assertEquals("claimed", result.getStatus());
        assertEquals(VERSION + 1, result.getWorkItemVersion());
        assertEquals(1, result.getAttemptCount());
        assertFalse(result.isIdempotentReplay());
        assertFalse(result.toString().contains(NEW_TOKEN));
        assertEquals(NEW_TOKEN, captureUpdate().getLeaseToken());
        assertNotEquals(source.getCommandId(), result.getCommandId());
        assertEquals(source.getCommandId(), inserted.get().getSourceCommandId());
        assertFalse(inserted.get().getLeaseFenceSha256().contains(NEW_TOKEN));

        ArgumentCaptor<AgentCommandDraft> draft = ArgumentCaptor.forClass(AgentCommandDraft.class);
        verify(commandWriter).writeAuthorizedHall(draft.capture(), eq(COORDINATOR));
        assertEquals(TARGET, draft.getValue().targetAgentId());
        assertEquals(WORK, draft.getValue().workItemId());
        assertEquals(AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                draft.getValue().commandType());
        String commandBytes = new String(AgentCommandCanonicalCodec.businessBytes(draft.getValue()),
                StandardCharsets.UTF_8);
        assertFalse(commandBytes.contains(NEW_TOKEN));
        assertFalse(commandBytes.contains("authoritative_lease_expired"));
        AgentHallCommandPayload payload = (AgentHallCommandPayload) draft.getValue().payload();
        assertEquals("evt_reassigned", payload.triggerEventId());
        assertFalse(payload.instruction().codePoints().anyMatch(Character::isISOControl));
        assertTrue(payload.instruction().contains(workItem.getTitle()));
        assertTrue(payload.instruction().contains(workItem.getDescription()));
    }

    @Test
    void liveLeaseNeverAuthorizesReassignmentForNoAckOrTransportExhaustion() {
        source.setStatus("SENT").setAttemptCount(Integer.MAX_VALUE);
        workItem.setLeaseUntil(NOW + 1);
        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                        TASK, WORK, "reassign-key-0001", request(TARGET)));
        assertEquals(AgentWorkItemReassignmentException.Reason.LEASE_NOT_EXPIRED,
                failure.getReason());
        verify(workItemDao, never()).reassignExpiredLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
        verifyNoInteractions(eventWriter, commandWriter);
    }

    @Test
    void exhaustedDomainBudgetRejectsWithoutWrites() {
        workItem.setAttemptCount(1).setMaxAttempts(2);
        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                        TASK, WORK, "reassign-key-0001", request(TARGET)));
        assertEquals(AgentWorkItemReassignmentException.Reason.DOMAIN_ATTEMPTS_EXHAUSTED,
                failure.getReason());
        verify(workItemDao, never()).reassignExpiredLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
        verifyNoInteractions(eventWriter, commandWriter);
    }

    @Test
    void permanentReceiptReplaysBeforeLeaseOrTransportMutation() {
        service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        when(reassignmentDao.findByReassignmentIdForUpdate(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), anyString()))
                .thenReturn(receipt);
        root.setTaskVersion(99L).setRewardStatus("completed");
        workItem.setVersion(88L).setStatus("completed").setLeaseToken(null).setLeaseUntil(null);

        var replay = service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));

        assertTrue(replay.isIdempotentReplay());
        assertEquals(receipt.getCommandId(), replay.getCommandId());
        verify(commandWriter, org.mockito.Mockito.times(1))
                .writeAuthorizedHall(any(), eq(COORDINATOR));
        verify(reassignmentDao, org.mockito.Mockito.times(1)).insert(any());
    }

    @Test
    void sameKeyChangedTargetConflictsWithoutLookingAtCurrentLease() {
        service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        when(reassignmentDao.findByReassignmentIdForUpdate(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), anyString()))
                .thenReturn(receipt);
        AgentWorkItemReassignmentRequestDTO changed = request(PREVIOUS);
        changed.setExpectedPreviousAgentId(TARGET);
        changed.setSourceCommandId("cmd_changed");

        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                        TASK, WORK, "reassign-key-0001", changed));
        assertEquals(AgentWorkItemReassignmentException.Reason.IDEMPOTENCY_CONFLICT,
                failure.getReason());
    }

    @Test
    void staleTaskAndWorkItemVersionsFailClosed() {
        AgentWorkItemReassignmentRequestDTO staleTask = request(TARGET);
        staleTask.setExpectedTaskVersion(6L);
        assertEquals(AgentWorkItemReassignmentException.Reason.VERSION_CONFLICT,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0001", staleTask)).getReason());
        AgentWorkItemReassignmentRequestDTO staleWork = request(TARGET);
        staleWork.setExpectedWorkItemVersion(VERSION - 1);
        assertEquals(AgentWorkItemReassignmentException.Reason.VERSION_CONFLICT,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0002", staleWork)).getReason());
    }

    @Test
    void wrongCoordinatorOrSourceTargetFailsWithoutCas() {
        root.setCoordinatorAgentId(TARGET);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0001", request(TARGET))).getReason());
        root.setCoordinatorAgentId(COORDINATOR);
        source.setTargetAgentId(TARGET);
        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_SOURCE_COMMAND,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0002", request(TARGET))).getReason());
        verify(workItemDao, never()).reassignExpiredLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void targetMembershipRuntimeAndHostingAdmissionEachFailClosedBeforeCas() {
        when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(null);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-member", request(TARGET))).getReason());

        when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET));
        when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, TARGET))
                .thenThrow(new IllegalStateException("cross-scope runtime"));
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-runtime", request(TARGET))).getReason());

        org.mockito.Mockito.doReturn(runtime(TARGET)).when(agentService)
                .requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, TARGET);
        org.mockito.Mockito.doThrow(new IllegalStateException("hosting denied"))
                .when(agentService).requireHostingNewWork(TENANT, CLIENT, TARGET);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-hosting", request(TARGET))).getReason());

        verify(workItemDao, never()).reassignExpiredLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void oldCommandOrOldLeaseFenceCannotReadNewLease() {
        AgentWorkItemReassignmentEntity receipt = receipt("rsn-old", "cmd-new",
                TARGET, "0".repeat(64));
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, TASK, WORK, "rsn-old")).thenReturn(receipt);
        AgentWorkItemReassignmentLeaseRequestDTO request = leaseRequest("cmd-old", VERSION);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.readLease(TENANT, CLIENT, TARGET, TASK, WORK,
                                "rsn-old", request)).getReason());

        request.setCommandId("cmd-new");
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.readLease(TENANT, CLIENT, TARGET, TASK, WORK,
                                "rsn-old", request)).getReason());
        verifyNoInteractions(leaseService);
    }

    @Test
    void exactTargetCommandAndFenceCanStartWithoutCallerSupplyingToken() {
        service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);
        AgentWorkItemLeaseDTO started = new AgentWorkItemLeaseDTO();
        started.setTaskId(TASK);
        started.setWorkItemId(WORK);
        started.setAgentId(TARGET);
        started.setStatus("running");
        started.setLeaseToken(NEW_TOKEN);
        started.setLeaseUntil(NOW + 300_000);
        started.setVersion(VERSION + 2);
        started.setAttemptCount(1);
        started.setMaxAttempts(3);
        started.setChangedAt(NOW);
        when(leaseService.start(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(started);

        var result = service.startLease(TENANT, CLIENT, TARGET, TASK, WORK,
                receipt.getReassignmentId(), leaseRequest(receipt.getCommandId(), VERSION + 1));
        assertEquals(NEW_TOKEN, result.getLeaseToken());
        assertEquals("running", result.getStatus());
        var command = ArgumentCaptor.forClass(cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO.class);
        verify(leaseService).start(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), command.capture());
        assertEquals(NEW_TOKEN, command.getValue().getLeaseToken());
    }


    @Test
    void exactTargetCommandAndFenceCanReadAndHeartbeatWithoutCallerToken() {
        service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);

        var read = service.readLease(TENANT, CLIENT, TARGET, TASK, WORK,
                receipt.getReassignmentId(), leaseRequest(receipt.getCommandId(), VERSION + 1));
        assertEquals(NEW_TOKEN, read.getLeaseToken());
        assertEquals(VERSION + 1, read.getWorkItemVersion());

        AgentWorkItemLeaseDTO renewed = new AgentWorkItemLeaseDTO();
        renewed.setTaskId(TASK);
        renewed.setWorkItemId(WORK);
        renewed.setAgentId(TARGET);
        renewed.setStatus("claimed");
        renewed.setLeaseToken(NEW_TOKEN);
        renewed.setLeaseUntil(NOW + 400_000);
        renewed.setVersion(VERSION + 2);
        renewed.setAttemptCount(1);
        renewed.setMaxAttempts(3);
        renewed.setChangedAt(NOW);
        when(leaseService.heartbeat(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(renewed);
        AgentWorkItemReassignmentLeaseRequestDTO heartbeat =
                leaseRequest(receipt.getCommandId(), VERSION + 1);
        heartbeat.setLeaseDurationMillis(400_000L);

        var result = service.heartbeatLease(TENANT, CLIENT, TARGET, TASK, WORK,
                receipt.getReassignmentId(), heartbeat);
        assertEquals(NOW + 400_000, result.getLeaseUntil());
        var command = ArgumentCaptor.forClass(cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO.class);
        verify(leaseService).heartbeat(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), command.capture());
        assertEquals(NEW_TOKEN, command.getValue().getLeaseToken());
        assertEquals(400_000L, command.getValue().getLeaseDurationMillis());
    }

    @Test
    void inconsistentHeartbeatVersionAndExpiryShapeFailsClosed() {
        service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);
        AgentWorkItemLeaseDTO poisoned = new AgentWorkItemLeaseDTO();
        poisoned.setTaskId(TASK);
        poisoned.setWorkItemId(WORK);
        poisoned.setAgentId(TARGET);
        poisoned.setStatus("claimed");
        poisoned.setLeaseToken(NEW_TOKEN);
        poisoned.setLeaseUntil(NOW + 400_000);
        poisoned.setVersion(VERSION + 1);
        poisoned.setAttemptCount(1);
        poisoned.setMaxAttempts(3);
        poisoned.setChangedAt(NOW);
        when(leaseService.heartbeat(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(poisoned);
        AgentWorkItemReassignmentLeaseRequestDTO heartbeat =
                leaseRequest(receipt.getCommandId(), VERSION + 1);
        heartbeat.setLeaseDurationMillis(400_000L);

        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.heartbeatLease(TENANT, CLIENT, TARGET, TASK, WORK,
                        receipt.getReassignmentId(), heartbeat));
        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_PERSISTED_STATE,
                failure.getReason());
    }

    @Test
    void inconsistentLeaseDelegateCannotLeakOrReplaceTheReceiptBoundCredential() {
        service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);
        AgentWorkItemLeaseDTO poisoned = new AgentWorkItemLeaseDTO();
        poisoned.setTaskId(TASK);
        poisoned.setWorkItemId(WORK);
        poisoned.setAgentId(TARGET);
        poisoned.setStatus("running");
        poisoned.setLeaseToken("other-secret-token");
        poisoned.setLeaseUntil(NOW + 300_000);
        poisoned.setVersion(VERSION + 2);
        poisoned.setAttemptCount(1);
        poisoned.setMaxAttempts(3);
        poisoned.setChangedAt(NOW);
        when(leaseService.start(eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), any()))
                .thenReturn(poisoned);

        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.startLease(TENANT, CLIENT, TARGET, TASK, WORK,
                        receipt.getReassignmentId(),
                        leaseRequest(receipt.getCommandId(), VERSION + 1)));
        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_PERSISTED_STATE,
                failure.getReason());
    }

    @Test
    void sensitiveOperatorReasonIsRejectedBeforeAnyLockOrWrite() {
        AgentWorkItemReassignmentRequestDTO request = request(TARGET);
        request.setReason("Authorization: Bearer secret");
        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_REQUEST,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-secret", request)).getReason());
        verifyNoInteractions(eventWriter, commandWriter);
    }

    private AgentTaskWorkItemDTO captureUpdate() {
        ArgumentCaptor<AgentTaskWorkItemDTO> update = ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao).reassignExpiredLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK), eq(WORK), eq(PREVIOUS), eq(TOKEN),
                eq("claimed"), eq(NOW - 1), eq(VERSION), eq(NOW), update.capture());
        return update.getValue();
    }

    private AgentTaskMetaEntity root() {
        AgentTaskMetaEntity value = new AgentTaskMetaEntity()
                .setTaskId(TASK).setRewardStatus("running")
                .setCoordinatorAgentId(COORDINATOR).setTaskVersion(7L)
                .setCurrentEventVersion(0L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        return value;
    }

    private AgentTaskMemberEntity member(String agent) {
        AgentTaskMemberEntity value = new AgentTaskMemberEntity()
                .setTaskId(TASK).setAgentId(agent)
                .setMemberRole(agent.equals(COORDINATOR) ? "coordinator" : "worker")
                .setMemberStatus("working").setAssignmentSource("manual").setVersion(0L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        return value;
    }

    private AgentRuntimeDTO runtime(String agent) {
        AgentRuntimeDTO value = new AgentRuntimeDTO();
        value.setAgentId(agent);
        value.setStatus("offline");
        return value;
    }

    private AgentTaskWorkItemEntity workItem() {
        AgentTaskWorkItemEntity value = new AgentTaskWorkItemEntity()
                .setTaskId(TASK).setWorkItemId(WORK).setTitle("Implement E05")
                .setDescription("Use one exact CAS").setWorkType("implementation")
                .setAssigneeAgentId(PREVIOUS).setStatus("claimed")
                .setPriority(0).setRequiredItem(true).setLeaseToken(TOKEN)
                .setLeaseUntil(NOW - 1).setAttemptCount(0).setMaxAttempts(3)
                .setVersion(VERSION);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        return value;
    }

    private AgentCommandDeliveryEntity source(String status, int attempts) {
        long issued = NOW - 600_000;
        String intent = "source-intent";
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                TENANT, CLIENT, TASK, PREVIOUS, intent,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
        AgentCommandDraft draft = new AgentCommandDraft(
                1, commandId, TASK, intent, TENANT, CLIENT, TASK, WORK, PREVIOUS,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE, issued,
                issued + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, intent,
                new AgentHallCommandPayload("work_item_execute", "Execute original work item",
                        "juyiting", null, null, null, "autonomous", false, null));
        byte[] bytes = AgentCommandCanonicalCodec.businessBytes(draft);
        AgentCommandDeliveryEntity value = new AgentCommandDeliveryEntity()
                .setCommandId(commandId).setTaskId(TASK).setWorkItemId(WORK)
                .setTargetAgentId(PREVIOUS)
                .setCommandType(AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE)
                .setCommandPayload(bytes)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(bytes))
                .setStatus(status).setAttemptCount(attempts).setVersion(0L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        return value;
    }

    private AgentWorkItemReassignmentRequestDTO request(String target) {
        AgentWorkItemReassignmentRequestDTO value = new AgentWorkItemReassignmentRequestDTO();
        value.setExpectedTaskVersion(7L);
        value.setExpectedWorkItemVersion(VERSION);
        value.setExpectedPreviousAgentId(PREVIOUS);
        value.setTargetAgentId(target);
        value.setSourceCommandId(source.getCommandId());
        value.setReason("authoritative_lease_expired");
        return value;
    }

    private AgentWorkItemReassignmentLeaseRequestDTO leaseRequest(String commandId, long version) {
        AgentWorkItemReassignmentLeaseRequestDTO value = new AgentWorkItemReassignmentLeaseRequestDTO();
        value.setCommandId(commandId);
        value.setExpectedWorkItemVersion(version);
        return value;
    }

    private AgentWorkItemReassignmentEntity receipt(
            String id, String commandId, String target, String fence) {
        AgentWorkItemReassignmentEntity value = new AgentWorkItemReassignmentEntity()
                .setId(1L).setReassignmentId(id).setRequestSha256("1".repeat(64))
                .setTaskId(TASK).setWorkItemId(WORK).setOperatorSubject("operator-1")
                .setCoordinatorAgentId(COORDINATOR).setPreviousAgentId(PREVIOUS)
                .setTargetAgentId(target).setSourceCommandId(source.getCommandId())
                .setCommandId(commandId).setMessageId("msg").setOutboxEventId("outbox")
                .setExpectedWorkItemVersion(VERSION).setResultWorkItemVersion(VERSION + 1)
                .setTaskVersion(7L).setLeaseFenceSha256(fence)
                .setPreviousLeaseUntil(NOW - 1).setLeaseUntil(NOW + 300_000)
                .setAttemptCount(1).setMaxAttempts(3);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        value.setCreateTime(NOW);
        value.setUpdateTime(NOW);
        return value;
    }

    private record InlineTransaction(AgentTaskMetaEntity root)
            implements AgentTaskMutationTransaction {
        @Override
        public <T> T executeWithLockedTaskRoot(String tenantId, String clientId,
                String taskId, LockedTaskMutation<T> mutation) {
            return mutation.apply(root);
        }
        @Override
        public <T> T executeWithLockedTaskRootForWorkItem(String tenantId, String clientId,
                String workItemId, LockedTaskMutation<T> mutation) {
            return mutation.apply(root);
        }
        @Override
        public <T> T executeAfterTaskRootReservation(String tenantId, String clientId,
                String taskId, TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
            return mutation.apply(root, reservation.reserve() == 1);
        }
    }
}
