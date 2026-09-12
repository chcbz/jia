package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemPlanConfirmRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanItemDTO;
import cn.jia.agent.entity.AgentWorkItemPlanSuggestRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentWorkItemPlanException;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentWorkItemPlanServiceImplTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long NOW = 1_789_210_000_000L;

    private AgentTaskMetaDao taskDao;
    private AgentTaskEventDao eventDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentIdentityService identityService;
    private AgentTaskMutationTransaction transaction;
    private AgentTaskEventWriter eventWriter;
    private AgentWorkItemPlanServiceImpl service;
    private AgentTaskMetaEntity task;
    private final Map<String, AgentTaskWorkItemEntity> stored = new HashMap<>();
    private final Map<String, AgentTaskEventEntity> storedEvents = new HashMap<>();

    @BeforeEach
    void setUp() {
        taskDao = mock(AgentTaskMetaDao.class);
        eventDao = mock(AgentTaskEventDao.class);
        memberDao = mock(AgentTaskMemberDao.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        identityService = mock(AgentIdentityService.class);
        transaction = mock(AgentTaskMutationTransaction.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        service = new AgentWorkItemPlanServiceImpl(taskDao, eventDao, memberDao, workItemDao,
                identityService, transaction, eventWriter, () -> NOW);

        task = task();
        when(taskDao.findByTaskId(TENANT, CLIENT, TASK)).thenReturn(task);
        when(taskDao.updateStatusByVersion(eq(TENANT), eq(CLIENT), eq(TASK),
                anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(identityService.requireCanonicalAgentIdInScope(TENANT, CLIENT, TENANT, ACTOR))
                .thenReturn(ACTOR);
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                TENANT, CLIENT, TENANT, List.of(ACTOR))).thenReturn(List.of(ACTOR));
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(coordinator());
        when(transaction.executeWithLockedTaskRoot(eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation =
                            invocation.getArgument(3);
                    return mutation.apply(task);
                });
        when(workItemDao.findByTaskAndWorkItemId(eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenAnswer(invocation -> stored.get(invocation.getArgument(3)));
        when(workItemDao.insert(eq(TENANT), eq(CLIENT), any())).thenAnswer(invocation -> {
            AgentTaskWorkItemDTO dto = invocation.getArgument(2);
            stored.put(dto.getWorkItemId(), entity(dto));
            return 1;
        });
        when(eventDao.findByEventId(eq(TENANT), eq(CLIENT), any()))
                .thenAnswer(invocation -> storedEvents.get(invocation.getArgument(2)));
        when(eventWriter.append(any())).thenAnswer(invocation -> {
            AgentTaskEventWriteCommand command = invocation.getArgument(0);
            storedEvents.put(command.getEventId(), event(command));
            return null;
        });
    }

    @Test
    void suggestionIsProviderFreeReadOnlyAndRequiresLaterConfirmation() {
        AgentWorkItemPlanViewDTO result = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Implement API safely"));

        assertTrue(result.isConfirmationRequired());
        assertFalse(result.isConfirmed());
        assertEquals("7", result.getExpectedTaskVersion());
        assertEquals(3, result.getItems().size());
        assertEquals("implementation", result.getItems().get(0).getWorkType());
        assertEquals(List.of("item-1"), result.getItems().get(1).getDependsOn());
        assertEquals(List.of("java", "mysql"),
                result.getItems().get(0).getRequiredAbilities());
        assertNull(result.getItems().get(0).getWorkItemId());
        verifyNoInteractions(transaction, eventDao, eventWriter);
        verify(workItemDao, never()).insert(any(), any(), any());
    }

    @Test
    void confirmationLocksRootThenCreatesOnlyUnassignedPendingOrReadyRowsAndBoundedEvents() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);

        AgentWorkItemPlanViewDTO result = service.confirm(
                TENANT, CLIENT, TASK, ACTOR, "confirm-key-0001", command);

        assertTrue(result.isConfirmed());
        assertFalse(result.isConfirmationRequired());
        assertFalse(result.isIdempotentReplay());
        assertEquals(3, stored.size());
        ArgumentCaptor<AgentTaskWorkItemDTO> inserts =
                ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao, times(3)).insert(eq(TENANT), eq(CLIENT), inserts.capture());
        assertEquals(1, inserts.getAllValues().stream()
                .filter(inserted -> "ready".equals(inserted.getStatus())).count());
        assertEquals(2, inserts.getAllValues().stream()
                .filter(inserted -> "pending".equals(inserted.getStatus())).count());
        List<String> insertedIds = inserts.getAllValues().stream()
                .map(AgentTaskWorkItemDTO::getWorkItemId).toList();
        assertEquals(insertedIds.stream().sorted().toList(), insertedIds);
        for (AgentTaskWorkItemDTO inserted : inserts.getAllValues()) {
            assertNull(inserted.getAssigneeAgentId());
            assertNull(inserted.getLeaseToken());
            assertNull(inserted.getLeaseUntil());
        }

        ArgumentCaptor<AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter, times(3)).append(events.capture());
        assertEquals(insertedIds, events.getAllValues().stream()
                .map(AgentTaskEventWriteCommand::getAggregateId).toList());
        for (AgentTaskEventWriteCommand event : events.getAllValues()) {
            assertEquals(TaskEventType.WORK_ITEM_CREATED, event.getEventType());
            assertEquals(TaskEventType.ActorType.SYSTEM, event.getActorType());
            assertNull(event.getActorId());
            assertTrue(event.getEventJson().contains("manual_confirmation"));
            assertFalse(event.getEventJson().contains("Build endpoint"));
            assertFalse(event.getEventJson().contains("java"));
        }

        var order = inOrder(transaction, identityService, memberDao,
                workItemDao, eventDao, eventWriter, taskDao);
        order.verify(transaction).executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any());
        order.verify(identityService).lockActiveCanonicalAgentIdsInScope(
                TENANT, CLIENT, TENANT, List.of(ACTOR));
        order.verify(memberDao).findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR);
        order.verify(workItemDao).findByTaskAndWorkItemId(
                eq(TENANT), eq(CLIENT), eq(TASK), any());
        order.verify(eventDao, times(3)).findByEventId(eq(TENANT), eq(CLIENT), any());
        order.verify(workItemDao, times(3)).insert(eq(TENANT), eq(CLIENT), any());
        order.verify(eventWriter, times(3)).append(any());
        order.verify(taskDao).updateStatusByVersion(eq(TENANT), eq(CLIENT), eq(TASK),
                eq(7L), eq("planning"), any(), any(), any());
    }

    @Test
    void exactDuplicateConfirmationIsReplayEvenAfterRuntimeStatusChanges() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        service.confirm(TENANT, CLIENT, TASK, ACTOR, "confirm-key-0002", command);
        stored.values().forEach(row -> row.setStatus("claimed").setVersion(1L)
                .setAssigneeAgentId("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .setLeaseToken("lease-current").setLeaseUntil(NOW + 1000));
        clearInvocations(workItemDao, eventWriter, transaction, taskDao);
        when(transaction.executeWithLockedTaskRoot(eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenAnswer(invocation -> ((AgentTaskMutationTransaction.LockedTaskMutation<?>)
                        invocation.getArgument(3)).apply(task));

        AgentWorkItemPlanViewDTO replay = service.confirm(
                TENANT, CLIENT, TASK, ACTOR, "confirm-key-0002", command);

        assertTrue(replay.isIdempotentReplay());
        assertEquals("claimed", replay.getItems().get(0).getStatus());
        assertEquals("1", replay.getItems().get(0).getVersion());
        verify(workItemDao, never()).insert(any(), any(), any());
        verify(taskDao, never()).updateStatusByVersion(any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void confirmationRequiresAnExactActiveIdentityLockBeforeChildWrites() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                TENANT, CLIENT, TENANT, List.of(ACTOR))).thenReturn(List.of());

        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-identity", command));

        verify(workItemDao, never()).insert(any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void replayWithoutAnyOneOfItsBoundedConfirmationEventsFailsClosed() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        AgentWorkItemPlanViewDTO confirmed = service.confirm(
                TENANT, CLIENT, TASK, ACTOR, "confirm-key-all-events", command);
        String nonAnchorId = confirmed.getItems().getLast().getWorkItemId();
        storedEvents.entrySet().removeIf(entry ->
                nonAnchorId.equals(entry.getValue().getAggregateId()));
        clearInvocations(workItemDao, eventWriter, eventDao, taskDao);

        assertReason(AgentWorkItemPlanException.Reason.INVALID_PERSISTED_STATE,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-all-events", command));

        verify(workItemDao, never()).insert(any(), any(), any());
        verify(taskDao, never()).updateStatusByVersion(any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void replayWithoutItsBoundedConfirmationEventFailsClosed() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        service.confirm(TENANT, CLIENT, TASK, ACTOR, "confirm-key-event", command);
        storedEvents.clear();
        clearInvocations(workItemDao, eventWriter, eventDao, taskDao);

        assertReason(AgentWorkItemPlanException.Reason.INVALID_PERSISTED_STATE,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-event", command));

        verify(workItemDao, never()).insert(any(), any(), any());
        verify(taskDao, never()).updateStatusByVersion(any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void sameIdempotencyKeyWithEditedPayloadConflictsWithoutMutation() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO original = confirmation(suggestion);
        service.confirm(TENANT, CLIENT, TASK, ACTOR, "confirm-key-0003", original);
        clearInvocations(workItemDao, eventWriter);
        AgentWorkItemPlanConfirmRequestDTO changed = confirmation(suggestion);
        changed.getItems().get(0).setTitle("Different title");

        AgentWorkItemPlanException error = assertThrows(AgentWorkItemPlanException.class,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-0003", changed));

        assertEquals(AgentWorkItemPlanException.Reason.IDEMPOTENCY_CONFLICT,
                error.getReason());
        verify(workItemDao, never()).insert(any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void sameIdempotencyKeyRejectsChangedSourceProvenanceEvenWithSameFinalItems() {
        AgentWorkItemPlanViewDTO firstSuggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanViewDTO otherSuggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Analyze another outcome"));
        AgentWorkItemPlanConfirmRequestDTO original = confirmation(firstSuggestion);
        service.confirm(TENANT, CLIENT, TASK, ACTOR, "confirm-key-source", original);
        clearInvocations(workItemDao, eventWriter, eventDao, taskDao);
        AgentWorkItemPlanConfirmRequestDTO changedSource = confirmation(otherSuggestion);
        changedSource.setItems(original.getItems());

        assertReason(AgentWorkItemPlanException.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-source", changedSource));

        verify(workItemDao, never()).insert(any(), any(), any());
        verify(taskDao, never()).updateStatusByVersion(any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void sameIdempotencyKeyCannotEscapeConflictByReplacingEveryItemKey() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO original = confirmation(suggestion);
        service.confirm(TENANT, CLIENT, TASK, ACTOR, "confirm-key-anchor", original);
        clearInvocations(workItemDao, eventWriter, taskDao);
        AgentWorkItemPlanConfirmRequestDTO changed = confirmation(suggestion);
        for (int index = 0; index < changed.getItems().size(); index++) {
            AgentWorkItemPlanItemDTO item = changed.getItems().get(index);
            item.setItemKey("replacement-" + index);
            item.setDependsOn(index == 0 ? List.of() : List.of("replacement-" + (index - 1)));
        }

        assertReason(AgentWorkItemPlanException.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-anchor", changed));

        verify(workItemDao, never()).insert(any(), any(), any());
        verify(taskDao, never()).updateStatusByVersion(any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void differentIdempotencyKeyCannotReuseAStaleSuggestedTaskVersion() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        service.confirm(TENANT, CLIENT, TASK, ACTOR, "confirm-key-version-a", command);
        task.setTaskVersion(8L);
        clearInvocations(workItemDao, eventWriter, taskDao);

        assertReason(AgentWorkItemPlanException.Reason.VERSION_CONFLICT,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-version-b", command));

        verify(workItemDao, never()).insert(any(), any(), any());
        verify(taskDao, never()).updateStatusByVersion(any(), any(), any(),
                anyLong(), any(), any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void malformedPersistedTaskAbilitiesFailAsUnavailableNotClientInput() {
        for (String abilities : List.of(
                "[\"java\",\"java\"]",
                "[\"java\"] {}")) {
            task.setRequiredAbilities(abilities);
            assertReason(AgentWorkItemPlanException.Reason.INVALID_PERSISTED_STATE,
                    () -> service.suggest(
                            TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint")));
        }

        verifyNoInteractions(transaction, eventWriter);
    }

    @Test
    void missingExplicitConfirmationAndCyclesFailBeforeTransaction() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        command.setConfirmed(false);
        assertReason(AgentWorkItemPlanException.Reason.INVALID_REQUEST,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-0004", command));

        command = confirmation(suggestion);
        command.getItems().get(0).setDependsOn(List.of("item-2"));
        AgentWorkItemPlanConfirmRequestDTO cycle = command;
        assertReason(AgentWorkItemPlanException.Reason.INVALID_REQUEST,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-0004", cycle));
        verifyNoInteractions(transaction);
    }

    @Test
    void sourcePlanCannotCrossTenantClientTaskActorOrVersion() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);

        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm("tenant-b", CLIENT, TASK, ACTOR,
                        "confirm-key-0005", command));
        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm(TENANT, "client-b", TASK, ACTOR,
                        "confirm-key-0005", command));
        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm(TENANT, CLIENT, "task-2", ACTOR,
                        "confirm-key-0005", command));
        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm(TENANT, CLIENT, TASK, "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        "confirm-key-0005", command));
        command.setExpectedTaskVersion("8");
        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-0005", command));
        verifyNoInteractions(transaction);
    }

    @Test
    void missingLockedTaskRootMapsToTheSameNonLeakingAccessFailure() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        when(transaction.executeWithLockedTaskRoot(eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenThrow(new AgentTaskCollaborationException(
                        AgentTaskCollaborationException.Reason.NOT_FOUND, "hidden scope"));

        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-missing", command));

        verify(workItemDao, never()).insert(any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void nonCoordinatorIdentityAndStaleTaskFailClosedWithoutChildWrites() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        task.setCoordinatorAgentId("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        assertReason(AgentWorkItemPlanException.Reason.NOT_FOUND_OR_FORBIDDEN,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-0006", command));
        task.setCoordinatorAgentId(ACTOR);
        task.setTaskVersion(8L);
        assertReason(AgentWorkItemPlanException.Reason.VERSION_CONFLICT,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-0006", command));
        verify(workItemDao, never()).insert(any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void partialPriorInsertStateFailsClosedInsteadOfCompletingAPossiblyTornPlan() {
        AgentWorkItemPlanViewDTO suggestion = service.suggest(
                TENANT, CLIENT, TASK, ACTOR, suggest("Build endpoint"));
        AgentWorkItemPlanConfirmRequestDTO command = confirmation(suggestion);
        service.confirm(TENANT, CLIENT, TASK, ACTOR, "confirm-key-0007", command);
        String retained = stored.keySet().iterator().next();
        AgentTaskWorkItemEntity row = stored.get(retained);
        stored.clear();
        stored.put(retained, row);
        clearInvocations(workItemDao, eventWriter);

        assertReason(AgentWorkItemPlanException.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.confirm(TENANT, CLIENT, TASK, ACTOR,
                        "confirm-key-0007", command));
        verify(workItemDao, never()).insert(any(), any(), any());
        verifyNoInteractions(eventWriter);
    }

    @Test
    void transactionContractKeepsSuggestionReadOnlyAndConfirmationRollbackCapable() throws Exception {
        Method suggest = AgentWorkItemPlanServiceImpl.class.getMethod("suggest",
                String.class, String.class, String.class, String.class,
                AgentWorkItemPlanSuggestRequestDTO.class);
        Method confirm = AgentWorkItemPlanServiceImpl.class.getMethod("confirm",
                String.class, String.class, String.class, String.class, String.class,
                AgentWorkItemPlanConfirmRequestDTO.class);
        assertTrue(suggest.getAnnotation(Transactional.class).readOnly());
        assertFalse(confirm.getAnnotation(Transactional.class).readOnly());
        assertEquals(List.of(Exception.class),
                List.of(confirm.getAnnotation(Transactional.class).rollbackFor()));
    }

    private AgentTaskMetaEntity task() {
        AgentTaskMetaEntity value = new AgentTaskMetaEntity()
                .setTaskId(TASK).setRewardStatus("planning")
                .setCoordinatorAgentId(ACTOR).setReviewRequired(true)
                .setRequiredAbilities("[\"java\",\"mysql\"]")
                .setTaskVersion(7L).setCurrentEventVersion(9L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        return value;
    }

    private AgentTaskMemberEntity coordinator() {
        AgentTaskMemberEntity value = new AgentTaskMemberEntity()
                .setTaskId(TASK).setAgentId(ACTOR).setMemberRole("coordinator")
                .setMemberStatus("working").setAssignmentSource("manual").setVersion(2L);
        value.setTenantId(TENANT);
        value.setClientId(CLIENT);
        return value;
    }

    private AgentWorkItemPlanSuggestRequestDTO suggest(String objective) {
        AgentWorkItemPlanSuggestRequestDTO request = new AgentWorkItemPlanSuggestRequestDTO();
        request.setObjective(objective);
        request.setMaxItems(4);
        request.setDependencyMode("sequential");
        return request;
    }

    private AgentWorkItemPlanConfirmRequestDTO confirmation(AgentWorkItemPlanViewDTO suggestion) {
        AgentWorkItemPlanConfirmRequestDTO request = new AgentWorkItemPlanConfirmRequestDTO();
        request.setConfirmed(true);
        request.setSourcePlanId(suggestion.getSourcePlanId());
        request.setSourcePlanDigest(suggestion.getSourcePlanDigest());
        request.setExpectedTaskVersion(suggestion.getExpectedTaskVersion());
        List<AgentWorkItemPlanItemDTO> items = new ArrayList<>();
        suggestion.getItems().forEach(view -> {
            AgentWorkItemPlanItemDTO item = new AgentWorkItemPlanItemDTO();
            item.setItemKey(view.getItemKey());
            item.setTitle(view.getTitle());
            item.setDescription(view.getDescription());
            item.setWorkType(view.getWorkType());
            item.setRequiredAbilities(view.getRequiredAbilities());
            item.setPriority(view.getPriority());
            item.setRequiredItem(view.getRequiredItem());
            item.setDependsOn(view.getDependsOn());
            item.setMaxAttempts(view.getMaxAttempts());
            items.add(item);
        });
        request.setItems(items);
        return request;
    }

    private AgentTaskWorkItemEntity entity(AgentTaskWorkItemDTO dto) {
        AgentTaskWorkItemEntity row = new AgentTaskWorkItemEntity()
                .setWorkItemId(dto.getWorkItemId()).setTaskId(dto.getTaskId())
                .setTitle(dto.getTitle()).setDescription(dto.getDescription())
                .setWorkType(dto.getWorkType()).setRequiredAbilities(dto.getRequiredAbilities())
                .setAssigneeAgentId(dto.getAssigneeAgentId()).setStatus(dto.getStatus())
                .setPriority(dto.getPriority()).setRequiredItem(dto.getRequiredItem())
                .setDependencyJson(dto.getDependencyJson()).setLeaseToken(dto.getLeaseToken())
                .setLeaseUntil(dto.getLeaseUntil()).setAttemptCount(dto.getAttemptCount())
                .setMaxAttempts(dto.getMaxAttempts()).setResultArtifactId(dto.getResultArtifactId())
                .setSubmittedAt(dto.getSubmittedAt()).setCompletedAt(dto.getCompletedAt())
                .setVersion(0L);
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        return row;
    }

    private AgentTaskEventEntity event(AgentTaskEventWriteCommand command) {
        AgentTaskEventEntity row = new AgentTaskEventEntity()
                .setTaskId(command.getTaskId())
                .setEventVersion((long) storedEvents.size() + 1)
                .setEventId(command.getEventId())
                .setEventType(command.getEventType())
                .setActorType(command.getActorType())
                .setActorId(command.getActorId())
                .setAggregateType(command.getAggregateType())
                .setAggregateId(command.getAggregateId())
                .setEventJson(command.getEventJson())
                .setOccurredAt(command.getOccurredAt());
        row.setTenantId(command.getTenantId());
        row.setClientId(command.getClientId());
        return row;
    }

    private void assertReason(AgentWorkItemPlanException.Reason reason, Runnable action) {
        assertEquals(reason, assertThrows(AgentWorkItemPlanException.class, action::run).getReason());
    }
}
