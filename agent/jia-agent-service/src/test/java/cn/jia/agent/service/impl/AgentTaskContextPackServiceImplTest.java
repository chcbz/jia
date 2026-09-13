package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskContextPackDao;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;
import cn.jia.agent.entity.AgentTaskContextPackTaskSourceRow;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.exception.AgentTaskContextPackException;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import cn.jia.agent.service.AgentTaskContextPackConversationSource;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskContextPackServiceImplTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "17";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private AgentTaskWorkspaceService workspaceService;
    private AgentTaskArtifactOutcomeService outcomeService;
    private AgentTaskContextPackDao contextPackDao;
    private AgentTaskContextPackConversationSource conversationSource;
    private AgentTaskContextPackServiceImpl service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(AgentTaskWorkspaceService.class);
        outcomeService = mock(AgentTaskArtifactOutcomeService.class);
        contextPackDao = mock(AgentTaskContextPackDao.class);
        conversationSource = mock(AgentTaskContextPackConversationSource.class);
        service = new AgentTaskContextPackServiceImpl(workspaceService, outcomeService,
                contextPackDao, Optional.of(conversationSource));
        when(workspaceService.snapshot(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(workspace("7"));
        when(contextPackDao.findTaskDescription(TENANT, CLIENT, TASK))
                .thenReturn(taskSource("Task title", "Task description"));
        when(outcomeService.listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, ACTOR, null, 51)).thenReturn(List.of(artifact()));
        when(conversationSource.findReference(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(AgentTaskContextPackConversationSource.ConversationReference
                        .available("31", 2, 1234L, false));
    }

    @Test
    void generatorOwnsOneRepeatableReadReadOnlyTransactionBoundary() throws Exception {
        Method method = AgentTaskContextPackServiceImpl.class.getMethod("generate",
                String.class, String.class, String.class, String.class, String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRED, transactional.propagation());
        assertEquals(Isolation.REPEATABLE_READ, transactional.isolation());
        assertTrue(transactional.readOnly());
    }

    @Test
    void stableInputProducesStableDigestAndDeterministicOrdering() {
        AgentTaskWorkspaceDTO workspace = workspace("7");
        AgentTaskWorkspaceDTO.Member z = member("z", "worker");
        AgentTaskWorkspaceDTO.Member a = member("a", "worker");
        workspace.setMembers(List.of(z, a));
        when(workspaceService.snapshot(TENANT, CLIENT, TASK, ACTOR)).thenReturn(workspace);

        var first = service.generate(TENANT, CLIENT, TASK, ACTOR, "7");
        var second = service.generate(TENANT, CLIENT, TASK, ACTOR, "7");

        assertEquals(first.getDigest(), second.getDigest());
        assertEquals(List.of("a", "z"), first.getMembers().getItems().stream()
                .map(value -> value.getAgentId()).toList());
        assertEquals(TENANT, first.getProvenance().getTenantId());
        assertEquals(CLIENT, first.getProvenance().getClientId());
        assertEquals(ACTOR, first.getProvenance().getActorAgentId());
    }

    @Test
    void expectedStaleVersionStopsBeforeTaskArtifactsAndConversationReads() {
        AgentTaskContextPackException failure = assertThrows(
                AgentTaskContextPackException.class,
                () -> service.generate(TENANT, CLIENT, TASK, ACTOR, "6"));

        assertEquals(AgentTaskContextPackException.Reason.STALE_VERSION,
                failure.getReason());
        verify(contextPackDao, never()).findTaskDescription(TENANT, CLIENT, TASK);
        verify(outcomeService, never()).listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, ACTOR, null, 51);
        verify(conversationSource, never()).findReference(TENANT, CLIENT, TASK, ACTOR);
    }

    @Test
    void sensitiveAndOversizedTextIsRedactedOrTruncatedWithoutRawPayloadFields() throws Exception {
        AgentTaskWorkspaceDTO workspace = workspace("7");
        AgentTaskWorkspaceDTO.WorkItem item = workItem("work-1", "Authorization: Bearer abcdefghijk");
        item.setDescription("x".repeat(10_000));
        item.setDependencyJson("[\"dep-b\",\"dep-a\"]");
        workspace.setWorkItems(List.of(item));
        AgentTaskWorkspaceDTO.Request request = request("request-1", "password=hunter2");
        workspace.setOpenRequests(List.of(request));
        when(workspaceService.snapshot(TENANT, CLIENT, TASK, ACTOR)).thenReturn(workspace);
        when(contextPackDao.findTaskDescription(TENANT, CLIENT, TASK))
                .thenReturn(taskSource("api_key=top-secret", "normal"));

        var result = service.generate(TENANT, CLIENT, TASK, ACTOR, null);
        String json = JsonUtil.getMapper().writeValueAsString(result);

        assertEquals("[REDACTED_SENSITIVE_TEXT]",
                result.getTaskDescription().getTitle().getValue());
        assertEquals(List.of("dep-a", "dep-b"),
                result.getWorkItems().getItems().get(0).getDependencyIds());
        assertEquals("TRUNCATED", result.getWorkItems().getStatus());
        assertFalse(json.contains("top-secret"));
        assertFalse(json.contains("hunter2"));
        assertFalse(json.contains("dependencyJson"));
        assertFalse(json.contains("leaseToken"));
        assertFalse(json.contains("storageUri"));
        assertFalse(json.contains("eventJson"));
        assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 100_000);
    }

    @Test
    void invalidDependencyDataIsExplicitlyUnavailableNotSilentlyComplete() {
        AgentTaskWorkspaceDTO workspace = workspace("7");
        workspace.setWorkItems(List.of(workItem("work-1", "normal")));
        workspace.getWorkItems().get(0).setDependencyJson("{\"secret\":true}");
        when(workspaceService.snapshot(TENANT, CLIENT, TASK, ACTOR)).thenReturn(workspace);

        var result = service.generate(TENANT, CLIENT, TASK, ACTOR, null);

        assertEquals("UNAVAILABLE", result.getWorkItems().getStatus());
        assertEquals("DEPENDENCY_DATA_INVALID", result.getWorkItems().getReason());
        assertTrue(result.getWorkItems().getItems().isEmpty());
    }

    @Test
    void onlyAcceptedMetadataCanEnterPackAndOutcomeLimitIsFixed() {
        AgentTaskArtifactOutcomeViewDTO superseded = artifact();
        superseded.setOutcomeState("superseded");
        when(outcomeService.listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, ACTOR, null, 51)).thenReturn(List.of(superseded));

        AgentTaskContextPackException failure = assertThrows(
                AgentTaskContextPackException.class,
                () -> service.generate(TENANT, CLIENT, TASK, ACTOR, null));

        assertEquals(AgentTaskContextPackException.Reason.CONTEXT_UNAVAILABLE,
                failure.getReason());
        assertFalse(failure.getMessage().contains("artifact-a"));
    }

    @Test
    void absentTaskPlanAndConversationSourceAreExplicitlyUnavailable() {
        service = new AgentTaskContextPackServiceImpl(workspaceService, outcomeService,
                contextPackDao, Optional.empty());
        when(contextPackDao.findTaskDescription(TENANT, CLIENT, TASK)).thenReturn(null);

        var result = service.generate(TENANT, CLIENT, TASK, ACTOR, null);

        assertEquals("UNAVAILABLE", result.getTaskDescription().getStatus());
        assertEquals("TASK_PLAN_SOURCE_NOT_FOUND", result.getTaskDescription().getReason());
        assertEquals("UNAVAILABLE", result.getConversation().getStatus());
        assertEquals("CONVERSATION_SOURCE_NOT_INSTALLED",
                result.getConversation().getReason());
        assertTrue(result.getConversation().isContentOmitted());
    }


    @Test
    void boundedCollectionsAndDependenciesAreExplicitlyTruncated() {
        AgentTaskWorkspaceDTO workspace = workspace("7");
        java.util.ArrayList<AgentTaskWorkspaceDTO.WorkItem> workItems =
                new java.util.ArrayList<>();
        String dependencies = "[" + java.util.stream.IntStream.range(0, 40)
                .mapToObj(index -> "\"dep-" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        for (int index = 0; index < 30; index++) {
            AgentTaskWorkspaceDTO.WorkItem item = workItem("work-" + index, "title");
            item.setDependencyJson(dependencies);
            workItems.add(item);
        }
        workspace.setWorkItems(workItems);
        when(workspaceService.snapshot(TENANT, CLIENT, TASK, ACTOR)).thenReturn(workspace);

        var result = service.generate(TENANT, CLIENT, TASK, ACTOR, null);

        assertEquals(25, result.getWorkItems().getItems().size());
        assertEquals("TRUNCATED", result.getWorkItems().getStatus());
        assertEquals("WORK_ITEM_LIMIT_REACHED", result.getWorkItems().getReason());
        assertEquals(25, result.getWorkItems().getItems().get(0).getDependencyIds().size());
        assertTrue(result.getWorkItems().getItems().get(0).isDependenciesTruncated());
    }

    @Test
    void nonNumericTaskPlanAndDraftArtifactEventsAreExplicitlyExcluded() {
        String legacyTask = "legacy-task";
        AgentTaskWorkspaceDTO workspace = workspace("7");
        workspace.getTask().setTaskId(legacyTask);
        AgentTaskWorkspaceDTO.Event draft = event("1", "ARTIFACT_PUBLISHED", "artifact-draft");
        AgentTaskWorkspaceDTO.Event accepted = event("2", "ARTIFACT_ACCEPTED", "artifact-a");
        workspace.setRecentEvents(List.of(draft, accepted));
        when(workspaceService.snapshot(TENANT, CLIENT, legacyTask, ACTOR)).thenReturn(workspace);
        when(contextPackDao.findTaskDescription(TENANT, CLIENT, legacyTask)).thenReturn(null);
        AgentTaskArtifactOutcomeViewDTO acceptedArtifact = artifact();
        acceptedArtifact.setTaskId(legacyTask);
        when(outcomeService.listAuthoritativeAccepted(
                TENANT, CLIENT, legacyTask, ACTOR, null, 51))
                .thenReturn(List.of(acceptedArtifact));
        when(conversationSource.findReference(TENANT, CLIENT, legacyTask, ACTOR))
                .thenReturn(AgentTaskContextPackConversationSource.ConversationReference
                        .unavailable("CONVERSATION_REFERENCE_UNAVAILABLE"));

        var result = service.generate(TENANT, CLIENT, legacyTask, ACTOR, null);

        assertEquals("TASK_PLAN_SOURCE_NON_NUMERIC", result.getTaskDescription().getReason());
        assertEquals(List.of("ARTIFACT_ACCEPTED"), result.getRecentEvents().getItems().stream()
                .map(value -> value.getEventType()).toList());
        assertEquals("ACCEPTED_ARTIFACT_EVENT_FILTER", result.getRecentEvents().getReason());
    }

    @Test
    void workspaceAclDenialDoesNotProbeDescriptionArtifactsOrConversation() {
        when(workspaceService.snapshot(TENANT, CLIENT, TASK, ACTOR)).thenThrow(
                new cn.jia.agent.exception.AgentTaskWorkspaceException(
                        cn.jia.agent.exception.AgentTaskWorkspaceException.Reason
                                .NOT_FOUND_OR_FORBIDDEN));

        AgentTaskContextPackException failure = assertThrows(
                AgentTaskContextPackException.class,
                () -> service.generate(TENANT, CLIENT, TASK, ACTOR, null));

        assertEquals(AgentTaskContextPackException.Reason.NOT_FOUND_OR_FORBIDDEN,
                failure.getReason());
        verify(contextPackDao, never()).findTaskDescription(TENANT, CLIENT, TASK);
        verify(outcomeService, never()).listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, ACTOR, null, 51);
        verify(conversationSource, never()).findReference(TENANT, CLIENT, TASK, ACTOR);
    }

    @Test
    void actorAndScopeAreDigestBound() {
        var first = service.generate(TENANT, CLIENT, TASK, ACTOR, null);
        when(workspaceService.snapshot(TENANT, CLIENT, TASK, "agt_other"))
                .thenReturn(workspace("7"));
        when(contextPackDao.findTaskDescription(TENANT, CLIENT, TASK))
                .thenReturn(taskSource("Task title", "Task description"));
        when(outcomeService.listAuthoritativeAccepted(
                TENANT, CLIENT, TASK, "agt_other", null, 51)).thenReturn(List.of(artifact()));
        when(conversationSource.findReference(TENANT, CLIENT, TASK, "agt_other"))
                .thenReturn(AgentTaskContextPackConversationSource.ConversationReference
                        .available("31", 2, 1234L, false));

        var second = service.generate(TENANT, CLIENT, TASK, "agt_other", null);

        assertNotEquals(first.getDigest(), second.getDigest());
    }

    private static AgentTaskWorkspaceDTO workspace(String currentVersion) {
        AgentTaskWorkspaceDTO result = new AgentTaskWorkspaceDTO();
        AgentTaskWorkspaceDTO.Task task = new AgentTaskWorkspaceDTO.Task();
        task.setTaskId(TASK);
        task.setVersion("3");
        result.setTask(task);
        result.setMembers(List.of(member(ACTOR, "coordinator")));
        result.setWorkItems(List.of());
        result.setOpenRequests(List.of());
        result.setRecentEvents(List.of());
        result.setCurrentVersion(currentVersion);
        return result;
    }

    private static AgentTaskWorkspaceDTO.Member member(String id, String role) {
        AgentTaskWorkspaceDTO.Member result = new AgentTaskWorkspaceDTO.Member();
        result.setAgentId(id);
        result.setRole(role);
        result.setStatus("working");
        result.setVersion("1");
        return result;
    }

    private static AgentTaskWorkspaceDTO.WorkItem workItem(String id, String title) {
        AgentTaskWorkspaceDTO.WorkItem result = new AgentTaskWorkspaceDTO.WorkItem();
        result.setWorkItemId(id);
        result.setTitle(title);
        result.setDescription("description");
        result.setWorkType("implementation");
        result.setStatus("running");
        result.setPriority(1);
        result.setRequiredItem(true);
        result.setDependencyJson("[]");
        result.setVersion("1");
        return result;
    }

    private static AgentTaskWorkspaceDTO.Request request(String id, String description) {
        AgentTaskWorkspaceDTO.Request result = new AgentTaskWorkspaceDTO.Request();
        result.setRequestId(id);
        result.setRequesterAgentId(ACTOR);
        result.setTargetType("role");
        result.setTargetId("coordinator");
        result.setRequestType("help");
        result.setStatus("open");
        result.setPriority(1);
        result.setTitle("Help");
        result.setDescription(description);
        result.setVersion("1");
        return result;
    }


    private static AgentTaskWorkspaceDTO.Event event(
            String version, String eventType, String aggregateId) {
        AgentTaskWorkspaceDTO.Event result = new AgentTaskWorkspaceDTO.Event();
        result.setVersion(version);
        result.setRedacted(false);
        result.setEventType(eventType);
        result.setActorType("agent");
        result.setActorId(ACTOR);
        result.setAggregateType("artifact");
        result.setAggregateId(aggregateId);
        result.setOccurredAt(version);
        return result;
    }

    private static AgentTaskContextPackTaskSourceRow taskSource(
            String title, String description) {
        AgentTaskContextPackTaskSourceRow result = new AgentTaskContextPackTaskSourceRow();
        result.setTenantId(TENANT);
        result.setClientId(CLIENT);
        result.setTaskId(TASK);
        result.setPlanId(17L);
        result.setTitle(title);
        result.setDescription(description);
        return result;
    }

    private static AgentTaskArtifactOutcomeViewDTO artifact() {
        AgentTaskArtifactOutcomeViewDTO result = new AgentTaskArtifactOutcomeViewDTO();
        result.setArtifactId("artifact-a");
        result.setTaskId(TASK);
        result.setWorkItemId("work-1");
        result.setProducerAgentId(ACTOR);
        result.setArtifactType("analysis");
        result.setTitle("Accepted result");
        result.setContentHash("a".repeat(64));
        result.setArtifactVersion(1);
        result.setVisibility("task_members");
        result.setCreatedAt(100L);
        result.setOutcomeState("accepted");
        result.setOutcomeVersion(1L);
        result.setDecisionId("decision-1");
        result.setDecidedByAgentId(ACTOR);
        result.setDecidedAt(200L);
        return result;
    }
}
