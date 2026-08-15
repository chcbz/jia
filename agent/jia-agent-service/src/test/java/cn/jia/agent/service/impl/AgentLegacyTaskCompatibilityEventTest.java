package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskAggregationDTO;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLegacyTaskCompatibilityEventTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock AgentTaskMetaDao taskMetaDao;
    @Mock AgentTaskMemberDao memberDao;
    @Mock AgentTaskWorkItemDao workItemDao;
    @Mock AgentTaskAggregationService aggregationService;
    @Mock AgentIdentityService identityService;
    @Mock AgentTaskMutationTransaction mutationTransaction;
    @Mock AgentTaskEventWriter eventWriter;

    private AgentTaskMetaEntity task;

    @BeforeEach
    void setUp() {
        task = root("open", 0L);
        org.mockito.Mockito.lenient().when(mutationTransaction.executeAfterTaskRootReservation(
                eq(TENANT), eq(CLIENT), eq(TASK), any(), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.TaskRootReservation reservation = invocation.getArgument(3);
                    boolean created = reservation.reserve() == 1;
                    AgentTaskMutationTransaction.ReservedTaskMutation<?> mutation = invocation.getArgument(4);
                    return mutation.apply(task, created);
                });
        org.mockito.Mockito.lenient().when(mutationTransaction.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
                    return mutation.apply(task);
                });
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                eq(TENANT), eq(CLIENT), eq(TENANT), anyList()))
                .thenAnswer(invocation -> List.copyOf(invocation.getArgument(3)));
    }

    @Test
    void assignAppendsTaskThenCanonicalUtf8MembersThenWorkItems() {
        String supplementary = "agt_😀";
        String privateUse = "agt_\uE000";
        when(taskMetaDao.reserveOpenTaskRoot(TENANT, CLIENT, TASK, 1_000L)).thenReturn(0);
        when(memberDao.listByTask(TENANT, CLIENT, TASK)).thenReturn(List.of());
        when(workItemDao.listByTask(TENANT, CLIENT, TASK, null, 500)).thenReturn(List.of());
        when(taskMetaDao.updateById(any())).thenReturn(1);
        when(memberDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(workItemDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);

        AgentLegacyTaskCompatibilityService.AssignOutcome outcome = service().assignResolved(
                TENANT, CLIENT, TASK, List.of(supplementary, privateUse), false);

        assertTrue(outcome.changed());
        ArgumentCaptor<AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter, org.mockito.Mockito.times(5)).append(events.capture());
        assertEquals(List.of(
                        TaskEventType.TASK_ASSIGNED,
                        TaskEventType.MEMBER_ACCEPTED, TaskEventType.MEMBER_ACCEPTED,
                        TaskEventType.WORK_ITEM_READY, TaskEventType.WORK_ITEM_READY),
                events.getAllValues().stream().map(AgentTaskEventWriteCommand::getEventType).toList());
        assertEquals(List.of(privateUse, supplementary),
                events.getAllValues().subList(1, 3).stream()
                        .map(AgentTaskEventWriteCommand::getAggregateId).toList());
        List<String> workItemIds = events.getAllValues().subList(3, 5).stream()
                .map(AgentTaskEventWriteCommand::getAggregateId).toList();
        assertTrue(compareUtf8Unsigned(workItemIds.get(0), workItemIds.get(1)) < 0);
        assertTrue(events.getAllValues().subList(3, 5).stream()
                .allMatch(event -> event.getEventJson().contains("assigneeAgentId")));
        assertEquals(TaskEventType.ActorType.SYSTEM, events.getAllValues().getFirst().getActorType());
    }

    @Test
    void reportAppendsMemberThenWorkItemBeforeAggregateAndDuplicateIsZeroEvent() {
        task = root("assigned", 0L);
        AgentTaskMemberEntity member = member("accepted", 0L);
        AgentTaskWorkItemEntity item = workItem("ready", 0L);
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                TENANT, CLIENT, TENANT, List.of(AGENT))).thenReturn(List.of(AGENT));
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, AGENT)).thenReturn(member);
        when(workItemDao.listByTaskAndAssignee(TENANT, CLIENT, TASK, AGENT, 500))
                .thenReturn(List.of(item));
        when(memberDao.updateByVersion(eq(TENANT), eq(CLIENT), eq(TASK), eq(AGENT), eq(0L), any()))
                .thenReturn(1);
        when(workItemDao.updateByVersion(eq(TENANT), eq(CLIENT), eq(item.getWorkItemId()), eq(0L), any()))
                .thenReturn(1);
        AgentTaskAggregationDTO aggregate = aggregate("running", 1L, true);
        when(aggregationService.aggregate(eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenReturn(aggregate);
        when(memberDao.listByTask(TENANT, CLIENT, TASK)).thenReturn(List.of(member));

        AgentLegacyTaskCompatibilityService.ReportOutcome outcome = service().reportResolved(
                TENANT, CLIENT, TASK, AGENT, "running", null);

        assertTrue(outcome.changed());
        ArgumentCaptor<AgentTaskEventWriteCommand> events =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter, org.mockito.Mockito.times(2)).append(events.capture());
        assertEquals(List.of(TaskEventType.MEMBER_WORKING, TaskEventType.WORK_ITEM_STARTED),
                events.getAllValues().stream().map(AgentTaskEventWriteCommand::getEventType).toList());

        org.mockito.Mockito.reset(eventWriter, aggregationService, memberDao, workItemDao);
        member.setMemberStatus("working").setStartedAt(1_000L).setVersion(1L);
        item.setStatus("running").setLeaseToken(legacyLeaseToken()).setLeaseUntil(Long.MAX_VALUE - 1)
                .setVersion(1L);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, AGENT)).thenReturn(member);
        when(workItemDao.listByTaskAndAssignee(TENANT, CLIENT, TASK, AGENT, 500))
                .thenReturn(List.of(item));
        when(aggregationService.aggregate(eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenReturn(aggregate("running", 0L, false));
        when(memberDao.listByTask(TENANT, CLIENT, TASK)).thenReturn(List.of(member));

        AgentLegacyTaskCompatibilityService.ReportOutcome duplicate = service().reportResolved(
                TENANT, CLIENT, TASK, AGENT, "running", null);
        assertFalse(duplicate.changed());
        verify(eventWriter, never()).append(any());
    }

    private AgentLegacyTaskCompatibilityService service() {
        return new AgentLegacyTaskCompatibilityService(
                taskMetaDao, memberDao, workItemDao, aggregationService, identityService,
                mutationTransaction, eventWriter, () -> 1_000L);
    }

    private AgentTaskMetaEntity root(String status, long version) {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity()
                .setTaskId(TASK).setRewardStatus(status).setTaskVersion(version)
                .setCurrentEventVersion(0L).setCollaborationMode("single")
                .setRiskLevel("low").setMaxAgents(1).setReviewRequired(false);
        root.setTenantId(TENANT);
        root.setClientId(CLIENT);
        return root;
    }

    private AgentTaskMemberEntity member(String status, long version) {
        AgentTaskMemberEntity member = new AgentTaskMemberEntity()
                .setTaskId(TASK).setAgentId(AGENT).setMemberRole("coordinator")
                .setMemberStatus(status).setAssignmentSource("manual")
                .setJoinedAt(900L).setAcceptedAt(900L).setVersion(version);
        member.setTenantId(TENANT);
        member.setClientId(CLIENT);
        return member;
    }

    private AgentTaskWorkItemEntity workItem(String status, long version) {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity()
                .setWorkItemId(defaultWorkItemId()).setTaskId(TASK)
                .setTitle("Legacy task execution").setWorkType("legacy_default")
                .setAssigneeAgentId(AGENT).setStatus(status).setPriority(0)
                .setRequiredItem(true).setDependencyJson("[]")
                .setAttemptCount(0).setMaxAttempts(3).setVersion(version);
        item.setTenantId(TENANT);
        item.setClientId(CLIENT);
        return item;
    }

    private AgentTaskAggregationDTO aggregate(String status, long version, boolean changed) {
        AgentTaskAggregationDTO dto = new AgentTaskAggregationDTO();
        dto.setTaskId(TASK);
        dto.setStatus(status);
        dto.setTaskVersion(version);
        dto.setChanged(changed);
        return dto;
    }

    private String defaultWorkItemId() {
        return "wi_legacy_" + digest(TASK + "\u0000" + AGENT);
    }

    private String legacyLeaseToken() {
        return "legacy_" + digest("lease\u0000" + TASK + "\u0000" + AGENT);
    }

    private int compareUtf8Unsigned(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int compared = Integer.compare(
                    Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
