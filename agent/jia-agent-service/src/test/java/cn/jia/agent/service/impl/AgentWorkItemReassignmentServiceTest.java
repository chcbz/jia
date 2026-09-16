package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.AgentWorkItemReassignmentDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private static final String TENANT = "0";
    private static final String CLIENT = "client-a";
    private static final String OWNER = "owner-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";
    private static final String PREVIOUS = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String COORDINATOR = "agt_cccccccccccccccccccccccccccccccc";
    private static final String LEGACY_PREVIOUS = "jyt-jiafewnnv58ec2379c-linchong";
    private static final String LEGACY_TARGET = "jyt-jiafewnnv58ec2379c-wuyong";
    private static final String LEGACY_COORDINATOR = "jyt-jiafewnnv58ec2379c-lujunyi";
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
            when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, OWNER, TASK, agent))
                    .thenReturn(member(agent));
            when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, OWNER, agent))
                    .thenReturn(runtime(agent));
        }
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                eq(TENANT), eq(CLIENT), eq(OWNER), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        doNothing().when(agentService).requireHostingNewWork(OWNER, CLIENT, TARGET);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, OWNER, TASK, WORK))
                .thenReturn(workItem);
        when(reassignmentDao.findSourceCommand(TENANT, CLIENT, OWNER, source.getCommandId()))
                .thenReturn(source);
        when(workItemDao.reassignExpiredLeaseByVersion(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
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
    void realLegacyCanonicalChainRunsReadStartCasHeartbeatCasAndRejectsRevokedReceiptReplay() {
        root.setCoordinatorAgentId(LEGACY_COORDINATOR);
        workItem.setAssigneeAgentId(LEGACY_PREVIOUS);
        source = sourceFor(LEGACY_PREVIOUS, "legacy-source-intent");
        when(reassignmentDao.findSourceCommand(TENANT, CLIENT, OWNER, source.getCommandId())).thenReturn(source);
        for (String agent : List.of(LEGACY_PREVIOUS, LEGACY_TARGET, LEGACY_COORDINATOR)) {
            when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, OWNER, TASK, agent))
                    .thenReturn(member(agent, LEGACY_COORDINATOR));
            when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, OWNER, agent))
                    .thenReturn(runtime(agent));
        }
        doNothing().when(agentService).requireHostingNewWork(OWNER, CLIENT, LEGACY_TARGET);
        when(commandWriter.writeAuthorizedHall(any(), eq(LEGACY_COORDINATOR)))
                .thenAnswer(invocation -> {
                    AgentCommandDraft draft = invocation.getArgument(0);
                    return new AgentCommandTransportWriteResult(
                            10, draft.commandId(), "msg-legacy", "outbox-legacy", false);
                });
        PersistedIdentityAuthority authority = new PersistedIdentityAuthority();
        authority.addDirect(LEGACY_PREVIOUS, AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, OWNER, CLIENT);
        authority.addDirect(LEGACY_TARGET, AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, OWNER, CLIENT);
        authority.addDirect(LEGACY_COORDINATOR, AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, OWNER, CLIENT);
        AgentWorkItemLeaseServiceImpl actualLease = actualLease(authority.service());
        leaseService = actualLease; // Real delegate, never mock away the active lease compatibility boundary.
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, OWNER, TASK, LEGACY_TARGET))
                .thenReturn(member(LEGACY_TARGET, LEGACY_COORDINATOR));
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, OWNER, WORK)).thenAnswer(inv -> workItem);
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, OWNER, TASK, WORK))
                .thenAnswer(inv -> workItem);
        when(workItemDao.updateActiveLeaseByVersion(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenAnswer(inv -> {
                    // Persist only on an exact scoped/version/status/token/time CAS. Replacing the
                    // row preserves the pre-mutation snapshot used by both real service layers.
                    if (!TENANT.equals(inv.getArgument(0)) || !CLIENT.equals(inv.getArgument(1))
                            || !TASK.equals(inv.getArgument(2)) || !WORK.equals(inv.getArgument(3))
                            || !workItem.getAssigneeAgentId().equals(inv.getArgument(4))
                            || !workItem.getLeaseToken().equals(inv.getArgument(5))
                            || !workItem.getStatus().equals(inv.getArgument(6))
                            || !workItem.getLeaseUntil().equals(inv.getArgument(7))
                            || !workItem.getVersion().equals(inv.getArgument(8))
                            || workItem.getLeaseUntil() <= (Long) inv.getArgument(9)) return 0;
                    AgentTaskWorkItemDTO update = inv.getArgument(10);
                    AgentTaskWorkItemEntity persisted = new AgentTaskWorkItemEntity()
                            .setTaskId(update.getTaskId()).setWorkItemId(update.getWorkItemId())
                            .setTitle(update.getTitle()).setDescription(update.getDescription())
                            .setWorkType(update.getWorkType()).setRequiredAbilities(update.getRequiredAbilities())
                            .setAssigneeAgentId(update.getAssigneeAgentId()).setStatus(update.getStatus())
                            .setPriority(update.getPriority()).setRequiredItem(update.getRequiredItem())
                            .setDependencyJson(update.getDependencyJson()).setLeaseToken(update.getLeaseToken())
                            .setLeaseUntil(update.getLeaseUntil()).setAttemptCount(update.getAttemptCount())
                            .setMaxAttempts(update.getMaxAttempts()).setResultArtifactId(update.getResultArtifactId())
                            .setSubmittedAt(update.getSubmittedAt()).setCompletedAt(update.getCompletedAt());
                    persisted.setTenantId(TENANT); persisted.setClientId(CLIENT);
                    persisted.setVersion(workItem.getVersion() + 1);
                    workItem = persisted;
                    return 1;
                });
        useIdentityAuthority(authority.service());

        AgentWorkItemReassignmentRequestDTO legacyRequest = request(
                LEGACY_PREVIOUS, LEGACY_TARGET, source.getCommandId());
        var created = service.reassign(TENANT, CLIENT, OWNER, "operator-legacy", LEGACY_COORDINATOR,
                TASK, WORK, "reassign-key-legacy", legacyRequest);
        assertEquals(LEGACY_TARGET, created.getTargetAgentId());
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        assertEquals(LEGACY_COORDINATOR, receipt.getCoordinatorAgentId());
        assertEquals(LEGACY_PREVIOUS, receipt.getPreviousAgentId());

        workItem.setAssigneeAgentId(LEGACY_TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, OWNER, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);
        var lease = service.readLease(TENANT, CLIENT, OWNER, LEGACY_TARGET, TASK, WORK,
                receipt.getReassignmentId(), leaseRequest(receipt.getCommandId(), VERSION + 1));
        assertEquals(LEGACY_TARGET, lease.getAgentId());
        assertEquals(NEW_TOKEN, lease.getLeaseToken());

        var startedLease = service.startLease(TENANT, CLIENT, OWNER, LEGACY_TARGET, TASK, WORK,
                receipt.getReassignmentId(), leaseRequest(receipt.getCommandId(), VERSION + 1));
        assertEquals("running", startedLease.getStatus());
        assertEquals("running", workItem.getStatus());
        assertEquals(VERSION + 2, workItem.getVersion());

        AgentWorkItemReassignmentLeaseRequestDTO heartbeat =
                leaseRequest(receipt.getCommandId(), VERSION + 2);
        heartbeat.setLeaseDurationMillis(400_000L);
        var renewedLease = service.heartbeatLease(TENANT, CLIENT, OWNER, LEGACY_TARGET, TASK, WORK,
                receipt.getReassignmentId(), heartbeat);
        assertEquals(NOW + 400_000, renewedLease.getLeaseUntil());
        assertEquals(NOW + 400_000, workItem.getLeaseUntil());
        assertEquals(VERSION + 3, workItem.getVersion());
        assertEquals(NEW_TOKEN, workItem.getLeaseToken());
        assertEquals(LEGACY_TARGET, workItem.getAssigneeAgentId());
        verify(workItemDao, org.mockito.Mockito.times(2)).updateActiveLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), eq(LEGACY_TARGET), eq(NEW_TOKEN),
                anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), eq(NOW), any());

        authority.suspend(LEGACY_TARGET);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-legacy", LEGACY_COORDINATOR,
                                TASK, WORK, "reassign-key-legacy", legacyRequest)).getReason());
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.readLease(TENANT, CLIENT, OWNER, LEGACY_TARGET, TASK, WORK,
                                receipt.getReassignmentId(),
                                leaseRequest(receipt.getCommandId(), VERSION + 1))).getReason());
        // Revocation is also enforced by the real delegate, even without its E05 wrapper.
        var revokedCommand = new cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO();
        revokedCommand.setAgentId(LEGACY_TARGET); revokedCommand.setLeaseToken(NEW_TOKEN);
        revokedCommand.setExpectedVersion(VERSION + 3); revokedCommand.setLeaseDurationMillis(500_000L);
        assertEquals(cn.jia.agent.exception.AgentTaskStateException.Reason.NOT_FOUND,
                assertThrows(cn.jia.agent.exception.AgentTaskStateException.class,
                        () -> actualLease.heartbeat(TENANT, CLIENT, OWNER, TASK, WORK, revokedCommand)).getReason());

        // ACTIVE is required for execution, not system cleanup: revoked direct-canonical
        // references still pass historical validation and can have their expired lease reclaimed.
        workItem.setLeaseUntil(NOW - 1);
        when(workItemDao.listExpiredLeases(TENANT, CLIENT, NOW, 1)).thenReturn(List.of(workItem));
        when(workItemDao.expireLeaseByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK),
                eq(LEGACY_TARGET), eq(NEW_TOKEN), eq("running"), eq(NOW - 1), eq(VERSION + 3), eq(NOW), any()))
                .thenReturn(1);
        var expired = actualLease.expireLeases(TENANT, CLIENT, 1);
        assertEquals(1, expired.getExpiredCount());
        assertEquals(1, expired.getRequeuedCount());
        verify(workItemDao).expireLeaseByVersion(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK),
                eq(LEGACY_TARGET), eq(NEW_TOKEN), eq("running"), eq(NOW - 1), eq(VERSION + 3), eq(NOW), any());
    }

    @Test
    void realLeaseDelegateRejectsAliasSystemUnknownInactiveAndCrossScopeBeforeCas() {
        for (String kind : List.of("alias", "system", "unknown", "classification", "inactive", "crossScope")) {
            PersistedIdentityAuthority authority = new PersistedIdentityAuthority();
            String agent = "jyt-denied-" + kind;
            switch (kind) {
                case "alias" -> authority.addAlias(agent, TARGET);
                case "system" -> authority.addDirect(agent, AgentConstants.IDENTITY_TYPE_SYSTEM,
                        AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, OWNER, CLIENT);
                case "classification" -> authority.addDirect(agent, "ALIAS",
                        AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, OWNER, CLIENT);
                case "inactive" -> authority.addDirect(agent, AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                        AgentConstants.IDENTITY_STATUS_SUSPENDED, AgentConstants.BINDING_STATUS_SUSPENDED, TENANT, CLIENT);
                case "crossScope" -> authority.addDirect(agent, AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                        AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, TENANT, "client-other");
                default -> { } // Unknown ID deliberately has no persisted identity.
            }
            when(memberDao.findByTaskAndAgent(TENANT, CLIENT, OWNER, TASK, agent)).thenReturn(member(agent));
            var command = new cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO();
            command.setAgentId(agent); command.setLeaseToken(NEW_TOKEN); command.setExpectedVersion(VERSION + 1);
            var actual = actualLease(authority.service());
            assertEquals(cn.jia.agent.exception.AgentTaskStateException.Reason.NOT_FOUND,
                    assertThrows(cn.jia.agent.exception.AgentTaskStateException.class,
                            () -> actual.start(TENANT, CLIENT, OWNER, TASK, WORK, command)).getReason(), kind);
        }
        verify(workItemDao, never()).updateActiveLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void realExpiryDoesNotTreatUnknownOrCrossScopeHistoryAsCanonical() {
        for (String scope : List.of(CLIENT, "client-other")) {
            PersistedIdentityAuthority authority = new PersistedIdentityAuthority();
            String unknown = "jyt-expiry-unknown";
            if (!CLIENT.equals(scope)) authority.addDirect(unknown, AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                    AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, TENANT, scope);
            workItem.setAssigneeAgentId(unknown);
            when(workItemDao.listExpiredLeases(TENANT, CLIENT, NOW, 1)).thenReturn(List.of(workItem));
            when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, OWNER, TASK, WORK)).thenReturn(workItem);
            assertEquals(cn.jia.agent.exception.AgentTaskStateException.Reason.INVALID_PERSISTED_STATE,
                    assertThrows(cn.jia.agent.exception.AgentTaskStateException.class,
                            () -> actualLease(authority.service()).expireLeases(TENANT, CLIENT, 1)).getReason());
        }
        verify(workItemDao, never()).expireLeaseByVersion(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    private AgentWorkItemLeaseServiceImpl actualLease(AgentIdentityService authority) {
        var actual = new AgentWorkItemLeaseServiceImpl(memberDao, workItemDao, new InlineTransaction(root),
                eventWriter, () -> NOW, () -> NEW_TOKEN, 900_000);
        actual.setIdentityService(authority);
        return actual;
    }

    @Test
    void persistedOpaqueAuthorityRemainsCompatible() {
        PersistedIdentityAuthority authority = new PersistedIdentityAuthority();
        for (String agent : List.of(PREVIOUS, TARGET, COORDINATOR)) {
            authority.addDirect(agent, AgentConstants.IDENTITY_TYPE_OPAQUE,
                    AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE,
                    TENANT, CLIENT);
        }
        useIdentityAuthority(authority.service());

        var result = service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-opaque", request(TARGET));
        assertEquals(TARGET, result.getTargetAgentId());
        assertFalse(result.isIdempotentReplay());
    }

    @Test
    void persistedAuthorityRejectsAliasSystemUnknownInactiveCrossScopeAndInvalidWireIds() {
        PersistedIdentityAuthority baseline = baselineOpaqueAuthority();
        String alias = "legacy-approved-alias";
        baseline.addAlias(alias, TARGET);
        assertTargetAuthorityRejected(alias, baseline, "reassign-key-alias");

        PersistedIdentityAuthority system = baselineOpaqueAuthority();
        system.addDirect(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, AgentConstants.IDENTITY_TYPE_SYSTEM,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE, OWNER, CLIENT);
        assertTargetAuthorityRejected(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, system,
                "reassign-key-system");

        assertTargetAuthorityRejected("unknown-direct-id", baselineOpaqueAuthority(),
                "reassign-key-unknown");

        PersistedIdentityAuthority noncanonical = baselineOpaqueAuthority();
        noncanonical.addDirect("noncanonical-direct", "ALIAS",
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE,
                TENANT, CLIENT);
        assertTargetAuthorityRejected("noncanonical-direct", noncanonical,
                "reassign-key-noncanonical");

        PersistedIdentityAuthority inactive = baselineOpaqueAuthority();
        inactive.addDirect("jyt-inactive", AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                AgentConstants.IDENTITY_STATUS_SUSPENDED, AgentConstants.BINDING_STATUS_SUSPENDED,
                TENANT, CLIENT);
        assertTargetAuthorityRejected("jyt-inactive", inactive, "reassign-key-inactive");

        PersistedIdentityAuthority crossScope = baselineOpaqueAuthority();
        crossScope.addDirect("jyt-cross-scope", AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE,
                TENANT, "client-b");
        assertTargetAuthorityRejected("jyt-cross-scope", crossScope, "reassign-key-cross");

        AgentWorkItemReassignmentRequestDTO invalid = request("invalid agent id");
        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_REQUEST,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-invalid", invalid)).getReason());
    }

    @Test
    void expiredLeaseReassignsExplicitTargetAndIgnoresTransportExhaustion() {
        var result = service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
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
        assertEquals("supervised", payload.autonomyLevel());
        assertTrue(payload.requiresApproval());
        assertEquals("evt_reassigned", payload.triggerEventId());
        assertEquals(Long.toString(VERSION + 1), payload.context().contextVersion());
        assertEquals(List.of(source.getCommandId()), payload.context().referenceIds());
        assertEquals(List.of("lease-expired", "reassignment"), payload.context().tags());
        assertEquals(AgentCommandCanonicalCodec.E05_REASSIGNMENT_BINDING_VERSION,
                payload.context().bindingVersion());
        assertEquals(inserted.get().getReassignmentId(), payload.context().reassignmentId());
        assertFalse(payload.instruction().codePoints().anyMatch(Character::isISOControl));
        assertTrue(payload.instruction().contains(workItem.getTitle()));
        assertTrue(payload.instruction().contains(workItem.getDescription()));
        String wireBytes = new String(
                AgentCommandCanonicalCodec.wireBytes(draft.getValue(), "msg-new"),
                StandardCharsets.UTF_8);
        assertTrue(wireBytes.contains("\"bindingVersion\":\"e05-reassignment-v1\""));
        assertTrue(wireBytes.contains("\"reassignmentId\":\""
                + inserted.get().getReassignmentId() + "\""));
        assertFalse(wireBytes.contains(NEW_TOKEN));
        assertFalse(wireBytes.contains("Authorization"));
    }

    @Test
    void highRiskWithoutReviewBoundaryFailsBeforeAnyReassignmentWrite() {
        root.setRiskLevel("high").setReviewRequired(false);

        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                        TASK, WORK, "reassign-key-0001", request(TARGET)));

        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_PERSISTED_STATE,
                failure.getReason());
        verify(workItemDao, never()).reassignExpiredLeaseByVersion(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyLong(), anyLong(), any());
        verify(commandWriter, never()).writeAuthorizedHall(any(), anyString());
    }

    @Test
    void liveLeaseNeverAuthorizesReassignmentForNoAckOrTransportExhaustion() {
        source.setStatus("SENT").setAttemptCount(Integer.MAX_VALUE);
        workItem.setLeaseUntil(NOW + 1);
        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
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
                () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
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
        service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        when(reassignmentDao.findByReassignmentIdForUpdate(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), anyString()))
                .thenReturn(receipt);
        root.setTaskVersion(99L).setRewardStatus("completed");
        workItem.setVersion(88L).setStatus("completed").setLeaseToken(null).setLeaseUntil(null);

        var replay = service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));

        assertTrue(replay.isIdempotentReplay());
        assertEquals(receipt.getCommandId(), replay.getCommandId());
        verify(commandWriter, org.mockito.Mockito.times(1))
                .writeAuthorizedHall(any(), eq(COORDINATOR));
        verify(reassignmentDao, org.mockito.Mockito.times(1)).insert(any());
    }

    @Test
    void sameKeyChangedTargetConflictsWithoutLookingAtCurrentLease() {
        service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        when(reassignmentDao.findByReassignmentIdForUpdate(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), anyString()))
                .thenReturn(receipt);
        AgentWorkItemReassignmentRequestDTO changed = request(PREVIOUS);
        changed.setExpectedPreviousAgentId(TARGET);
        changed.setSourceCommandId("cmd_changed");

        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
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
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0001", staleTask)).getReason());
        AgentWorkItemReassignmentRequestDTO staleWork = request(TARGET);
        staleWork.setExpectedWorkItemVersion(VERSION - 1);
        assertEquals(AgentWorkItemReassignmentException.Reason.VERSION_CONFLICT,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0002", staleWork)).getReason());
    }

    @Test
    void wrongCoordinatorOrSourceTargetFailsWithoutCas() {
        root.setCoordinatorAgentId(TARGET);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0001", request(TARGET))).getReason());
        root.setCoordinatorAgentId(COORDINATOR);
        source.setTargetAgentId(TARGET);
        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_SOURCE_COMMAND,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-0002", request(TARGET))).getReason());
        verify(workItemDao, never()).reassignExpiredLeaseByVersion(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void targetMembershipRuntimeAndHostingAdmissionEachFailClosedBeforeCas() {
        when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, OWNER, TASK, TARGET))
                .thenReturn(null);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-member", request(TARGET))).getReason());

        when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, OWNER, TASK, TARGET))
                .thenReturn(member(TARGET));
        when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, OWNER, TARGET))
                .thenThrow(new IllegalStateException("cross-scope runtime"));
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-runtime", request(TARGET))).getReason());

        org.mockito.Mockito.doReturn(runtime(TARGET)).when(agentService)
                .requireApiKeyOwnedAgentForUpdate(CLIENT, OWNER, TARGET);
        org.mockito.Mockito.doThrow(new IllegalStateException("hosting denied"))
                .when(agentService).requireHostingNewWork(OWNER, CLIENT, TARGET);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
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
                TENANT, CLIENT, OWNER, TASK, WORK, "rsn-old")).thenReturn(receipt);
        AgentWorkItemReassignmentLeaseRequestDTO request = leaseRequest("cmd-old", VERSION);
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.readLease(TENANT, CLIENT, OWNER, TARGET, TASK, WORK,
                                "rsn-old", request)).getReason());

        request.setCommandId("cmd-new");
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.readLease(TENANT, CLIENT, OWNER, TARGET, TASK, WORK,
                                "rsn-old", request)).getReason());
        verifyNoInteractions(leaseService);
    }

    @Test
    void exactTargetCommandAndFenceCanStartWithoutCallerSupplyingToken() {
        service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, OWNER, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);
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
        when(leaseService.start(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), any()))
                .thenReturn(started);

        var result = service.startLease(TENANT, CLIENT, OWNER, TARGET, TASK, WORK,
                receipt.getReassignmentId(), leaseRequest(receipt.getCommandId(), VERSION + 1));
        assertEquals(NEW_TOKEN, result.getLeaseToken());
        assertEquals("running", result.getStatus());
        var command = ArgumentCaptor.forClass(cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO.class);
        verify(leaseService).start(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), command.capture());
        assertEquals(NEW_TOKEN, command.getValue().getLeaseToken());
    }


    @Test
    void exactTargetCommandAndFenceCanReadAndHeartbeatWithoutCallerToken() {
        service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, OWNER, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);

        var read = service.readLease(TENANT, CLIENT, OWNER, TARGET, TASK, WORK,
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
        when(leaseService.heartbeat(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), any()))
                .thenReturn(renewed);
        AgentWorkItemReassignmentLeaseRequestDTO heartbeat =
                leaseRequest(receipt.getCommandId(), VERSION + 1);
        heartbeat.setLeaseDurationMillis(400_000L);

        var result = service.heartbeatLease(TENANT, CLIENT, OWNER, TARGET, TASK, WORK,
                receipt.getReassignmentId(), heartbeat);
        assertEquals(NOW + 400_000, result.getLeaseUntil());
        var command = ArgumentCaptor.forClass(cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO.class);
        verify(leaseService).heartbeat(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), command.capture());
        assertEquals(NEW_TOKEN, command.getValue().getLeaseToken());
        assertEquals(400_000L, command.getValue().getLeaseDurationMillis());
    }

    @Test
    void inconsistentHeartbeatVersionAndExpiryShapeFailsClosed() {
        service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, OWNER, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);
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
        when(leaseService.heartbeat(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), any()))
                .thenReturn(poisoned);
        AgentWorkItemReassignmentLeaseRequestDTO heartbeat =
                leaseRequest(receipt.getCommandId(), VERSION + 1);
        heartbeat.setLeaseDurationMillis(400_000L);

        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.heartbeatLease(TENANT, CLIENT, OWNER, TARGET, TASK, WORK,
                        receipt.getReassignmentId(), heartbeat));
        assertEquals(AgentWorkItemReassignmentException.Reason.INVALID_PERSISTED_STATE,
                failure.getReason());
    }

    @Test
    void inconsistentLeaseDelegateCannotLeakOrReplaceTheReceiptBoundCredential() {
        service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request(TARGET));
        AgentWorkItemReassignmentEntity receipt = inserted.get();
        workItem.setAssigneeAgentId(TARGET).setLeaseToken(NEW_TOKEN)
                .setLeaseUntil(NOW + 300_000).setAttemptCount(1).setVersion(VERSION + 1);
        when(reassignmentDao.findByReassignmentIdForUpdate(
                TENANT, CLIENT, OWNER, TASK, WORK, receipt.getReassignmentId())).thenReturn(receipt);
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
        when(leaseService.start(eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), any()))
                .thenReturn(poisoned);

        AgentWorkItemReassignmentException failure = assertThrows(
                AgentWorkItemReassignmentException.class,
                () -> service.startLease(TENANT, CLIENT, OWNER, TARGET, TASK, WORK,
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
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, "reassign-key-secret", request)).getReason());
        verifyNoInteractions(eventWriter, commandWriter);
    }

    private void useIdentityAuthority(AgentIdentityService authority) {
        identityService = authority;
        service = new AgentWorkItemReassignmentServiceImpl(
                reassignmentDao, memberDao, workItemDao, new InlineTransaction(root),
                identityService, agentService, eventWriter, commandWriter, leaseService,
                () -> NOW, () -> NEW_TOKEN, 300_000);
    }

    private PersistedIdentityAuthority baselineOpaqueAuthority() {
        PersistedIdentityAuthority authority = new PersistedIdentityAuthority();
        authority.addDirect(PREVIOUS, AgentConstants.IDENTITY_TYPE_OPAQUE,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE,
                TENANT, CLIENT);
        authority.addDirect(COORDINATOR, AgentConstants.IDENTITY_TYPE_OPAQUE,
                AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE,
                TENANT, CLIENT);
        return authority;
    }

    private void assertTargetAuthorityRejected(
            String target, PersistedIdentityAuthority authority, String idempotencyKey) {
        when(memberDao.findByTaskAndAgentForUpdate(TENANT, CLIENT, OWNER, TASK, target))
                .thenReturn(member(target));
        when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, OWNER, target))
                .thenReturn(runtime(target));
        doNothing().when(agentService).requireHostingNewWork(OWNER, CLIENT, target);
        useIdentityAuthority(authority.service());
        assertEquals(AgentWorkItemReassignmentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(AgentWorkItemReassignmentException.class,
                        () -> service.reassign(TENANT, CLIENT, OWNER, "operator-1", COORDINATOR,
                                TASK, WORK, idempotencyKey, request(target))).getReason());
    }

    private AgentTaskWorkItemDTO captureUpdate() {
        ArgumentCaptor<AgentTaskWorkItemDTO> update = ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao).reassignExpiredLeaseByVersion(
                eq(TENANT), eq(CLIENT), eq(OWNER), eq(TASK), eq(WORK), eq(PREVIOUS), eq(TOKEN),
                eq("claimed"), eq(NOW - 1), eq(VERSION), eq(NOW), update.capture());
        return update.getValue();
    }

    private AgentTaskMetaEntity root() {
        AgentTaskMetaEntity value = new AgentTaskMetaEntity()
                .setTaskId(TASK).setRewardStatus("running").setRiskLevel("low")
                .setCoordinatorAgentId(COORDINATOR).setTaskVersion(7L)
                .setCurrentEventVersion(0L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        value.setOwnerJiacn(OWNER);
        return value;
    }

    private AgentTaskMemberEntity member(String agent) {
        return member(agent, COORDINATOR);
    }

    private AgentTaskMemberEntity member(String agent, String coordinator) {
        AgentTaskMemberEntity value = new AgentTaskMemberEntity()
                .setTaskId(TASK).setAgentId(agent)
                .setMemberRole(agent.equals(coordinator) ? "coordinator" : "worker")
                .setMemberStatus("working").setAssignmentSource("manual").setVersion(0L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        value.setOwnerJiacn(OWNER);
        return value;
    }

    private AgentRuntimeDTO runtime(String agent) {
        AgentRuntimeDTO value = new AgentRuntimeDTO();
        value.setAgentId(agent);
        value.setStatus("offline");
        return value;
    }

    private AgentWorkItemLeaseDTO leaseMutation(
            String agentId, String status, long leaseUntil, long version) {
        AgentWorkItemLeaseDTO value = new AgentWorkItemLeaseDTO();
        value.setTaskId(TASK);
        value.setWorkItemId(WORK);
        value.setAgentId(agentId);
        value.setStatus(status);
        value.setLeaseToken(NEW_TOKEN);
        value.setLeaseUntil(leaseUntil);
        value.setVersion(version);
        value.setAttemptCount(1);
        value.setMaxAttempts(3);
        value.setChangedAt(NOW);
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
        value.setOwnerJiacn(OWNER);
        return value;
    }

    private AgentCommandDeliveryEntity source(String status, int attempts) {
        return sourceFor(PREVIOUS, "source-intent").setStatus(status).setAttemptCount(attempts);
    }

    private AgentCommandDeliveryEntity sourceFor(String targetAgentId, String intent) {
        long issued = NOW - 600_000;
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                TENANT, CLIENT, TASK, targetAgentId, intent,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
        AgentCommandDraft draft = new AgentCommandDraft(
                1, commandId, TASK, intent, TENANT, CLIENT, OWNER, TASK, WORK, targetAgentId,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE, issued,
                issued + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, intent,
                new AgentHallCommandPayload("work_item_execute", "Execute original work item",
                        "juyiting", null, null, null, "autonomous", false, null));
        byte[] bytes = AgentCommandCanonicalCodec.businessBytes(draft);
        AgentCommandDeliveryEntity value = new AgentCommandDeliveryEntity()
                .setCommandId(commandId).setTaskId(TASK).setWorkItemId(WORK)
                .setTargetAgentId(targetAgentId)
                .setCommandType(AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE)
                .setCommandPayload(bytes)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(bytes))
                .setStatus("DEAD").setAttemptCount(Integer.MAX_VALUE).setVersion(0L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        value.setOwnerJiacn(OWNER);
        return value;
    }

    private AgentWorkItemReassignmentRequestDTO request(String target) {
        return request(PREVIOUS, target, source.getCommandId());
    }

    private AgentWorkItemReassignmentRequestDTO request(
            String previous, String target, String sourceCommandId) {
        AgentWorkItemReassignmentRequestDTO value = new AgentWorkItemReassignmentRequestDTO();
        value.setExpectedTaskVersion(7L);
        value.setExpectedWorkItemVersion(VERSION);
        value.setExpectedPreviousAgentId(previous);
        value.setTargetAgentId(target);
        value.setSourceCommandId(sourceCommandId);
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

    private static final class PersistedIdentityAuthority {
        private final AgentIdentityRegistryDao registries = mock(AgentIdentityRegistryDao.class);
        private final AgentIdentityAliasDao aliases = mock(AgentIdentityAliasDao.class);
        private final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
        private final Map<String, AgentIdentityRegistryEntity> byCanonical = new HashMap<>();
        private final Map<Long, AgentPersonaBindingEntity> byBinding = new HashMap<>();
        private final Map<String, AgentIdentityAliasEntity> byAlias = new HashMap<>();
        private long nextId = 20;

        PersistedIdentityAuthority() {
            when(registries.findExactByCanonicalInScope(
                    anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> scoped(
                            inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
            when(registries.findExactByCanonicalInScopeForUpdate(
                    anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> scoped(
                            inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
            when(bindings.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> byBinding.get(inv.getArgument(0)));
            when(bindings.selectById(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> byBinding.get(inv.getArgument(0)));
            when(aliases.findExactActiveLegacyAlias(
                    anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
                        AgentIdentityAliasEntity alias = byAlias.get(inv.getArgument(3));
                        return alias != null && exactScope(alias.getTenantId(), alias.getClientId(),
                                alias.getOwnerJiacn(), inv.getArgument(0), inv.getArgument(1),
                                inv.getArgument(2)) ? alias : null;
                    });
        }

        AgentIdentityService service() {
            return new AgentIdentityServiceImpl(registries, aliases, bindings);
        }

        void addDirect(String agentId, String type, String lifecycle, int bindingStatus,
                String tenant, String client) {
            long bindingId = nextId++;
            AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity()
                    .setId(bindingId).setJiacn(tenant).setPersonaCode("persona-" + bindingId)
                    .setAgentId(agentId).setBoundAt(1L).setStatus(bindingStatus);
            binding.setTenantId(tenant); binding.setClientId(client);
            AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity()
                    .setId(100L + bindingId).setCanonicalAgentId(agentId).setCanonicalType(type)
                    .setLifecycleStatus(lifecycle).setOwnerJiacn(tenant).setBindingId(bindingId)
                    .setProvisionedAt(1L).setActivatedAt(2L).setAuditReason("test identity authority");
            if (AgentConstants.IDENTITY_STATUS_SUSPENDED.equals(lifecycle)) identity.setSuspendedAt(3L);
            identity.setTenantId(tenant); identity.setClientId(client);
            byBinding.put(bindingId, binding); byCanonical.put(agentId, identity);
        }

        void addAlias(String alias, String canonical) {
            AgentIdentityRegistryEntity identity = byCanonical.get(canonical);
            if (identity == null) {
                addDirect(canonical, AgentConstants.IDENTITY_TYPE_OPAQUE,
                        AgentConstants.IDENTITY_STATUS_ACTIVE, AgentConstants.BINDING_STATUS_ACTIVE,
                        TENANT, CLIENT);
                identity = byCanonical.get(canonical);
            }
            AgentIdentityAliasEntity value = new AgentIdentityAliasEntity().setId(nextId++)
                    .setRegistryId(identity.getId()).setCanonicalAgentId(canonical)
                    .setAliasType(AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID)
                    .setAliasValue(alias).setAliasStatus(AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE)
                    .setValidFrom(1L).setOwnerJiacn(identity.getOwnerJiacn())
                    .setAuditReason("approved alias fixture");
            value.setTenantId(identity.getTenantId()); value.setClientId(identity.getClientId());
            byAlias.put(alias, value);
        }

        void suspend(String canonical) {
            AgentIdentityRegistryEntity identity = byCanonical.get(canonical);
            identity.setLifecycleStatus(AgentConstants.IDENTITY_STATUS_SUSPENDED).setSuspendedAt(3L);
            byBinding.get(identity.getBindingId()).setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        }

        private AgentIdentityRegistryEntity scoped(
                String tenant, String client, String owner, String canonical) {
            AgentIdentityRegistryEntity identity = byCanonical.get(canonical);
            return identity != null && exactScope(identity.getTenantId(), identity.getClientId(),
                    identity.getOwnerJiacn(), tenant, client, owner) ? identity : null;
        }

        private static boolean exactScope(String actualTenant, String actualClient, String actualOwner,
                String tenant, String client, String owner) {
            return tenant.equals(actualTenant) && client.equals(actualClient) && owner.equals(actualOwner);
        }
    }

    private record InlineTransaction(AgentTaskMetaEntity root)
            implements AgentTaskMutationTransaction {
        @Override
        public <T> T executeWithLockedTaskRoot(String tenantId, String clientId,
                String taskId, LockedTaskMutation<T> mutation) {
            return mutation.apply(root);
        }
        @Override
        public <T> T executeWithLockedTaskRootInOwnerScope(
                String tenantId, String clientId, String ownerJiacn, String taskId,
                LockedTaskMutation<T> mutation) {
            return mutation.apply(root);
        }
        @Override
        public <T> T executeWithLockedTaskRootForWorkItem(String tenantId, String clientId,
                String workItemId, LockedTaskMutation<T> mutation) {
            return mutation.apply(root);
        }
        @Override
        public <T> T executeWithLockedTaskRootForWorkItemInOwnerScope(
                String tenantId, String clientId, String ownerJiacn, String workItemId,
                LockedTaskMutation<T> mutation) {
            return mutation.apply(root);
        }
        @Override
        public <T> T executeAfterTaskRootReservation(String tenantId, String clientId,
                String taskId, TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
            return mutation.apply(root, reservation.reserve() == 1);
        }
        @Override
        public <T> T executeAfterTaskRootReservationInOwnerScope(
                String tenantId, String clientId, String ownerJiacn, String taskId,
                TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
            return mutation.apply(root, reservation.reserve() == 1);
        }
    }
}
