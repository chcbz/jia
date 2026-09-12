package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

class JuyitingConversationScopeServiceTest extends BaseMockTest {
    @Mock BuiltinHallAgentSupport builtinHallAgentSupport;
    @Mock AgentService agentService;

    @Test
    void resolvesRequestFieldsBeforeDisplayMetadataFallbacks() {
        ChatMessageDTO request = bounty("372", List.of("agent-wuyong", "agent-linchong"));
        request.setMetadata(Map.of(
                "mode", "public", "scopeKey", "task:999", "selectedTaskId", "999",
                "participantAgentIds", List.of("forged-agent")));

        JuyitingConversationScope scope = service().resolve(request);

        assertEquals("bounty", scope.scopeType());
        assertEquals("task:372", scope.scopeKey());
        assertEquals("372", scope.taskId());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), scope.targetAgentIds());
    }

    @Test
    void forgedParticipantMetadataNeverAuthorizesTarget() {
        when(agentService.listTaskWritableMemberAgentIds("tenant-a", "client-a", "372"))
                .thenReturn(List.of("agent-wuyong"));
        ChatMessageDTO request = bounty("372", List.of("agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-linchong")));

        assertThrows(IllegalStateException.class, () -> authorize(request));
    }

    @Test
    void bountyRequiresCanonicalTaskAndExactTaskScope() {
        ChatMessageDTO omitted = bounty(null, List.of("agent-wuyong"));
        omitted.setConversationScopeKey("public");
        assertThrows(IllegalStateException.class, () -> authorize(omitted));

        when(agentService.listTaskWritableMemberAgentIds("tenant-a", "client-a", "372"))
                .thenReturn(List.of("agent-wuyong"));
        ChatMessageDTO crossTask = bounty("372", List.of("agent-wuyong"));
        crossTask.setConversationScopeKey("task:999");
        assertThrows(IllegalStateException.class, () -> authorize(crossTask));
    }

    @Test
    void taskMemberQueryFailureAndEmptyTaskFailClosed() {
        ChatMessageDTO request = bounty("372", List.of("agent-wuyong"));
        when(agentService.listTaskWritableMemberAgentIds("tenant-a", "client-a", "372"))
                .thenThrow(new IllegalStateException("query failed"));
        assertThrows(IllegalStateException.class, () -> authorize(request));

        org.mockito.Mockito.doReturn(List.of()).when(agentService)
                .listTaskWritableMemberAgentIds("tenant-a", "client-a", "372");
        assertThrows(IllegalStateException.class, () -> authorize(request));
    }

    @Test
    void legalTaskTargetsUseAuthoritativeMembersAndSupportMultipleTargets() {
        when(agentService.listTaskWritableMemberAgentIds("tenant-a", "client-a", "372"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
        ChatMessageDTO request = bounty("372", List.of("agent-wuyong", "agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("forged-agent")));

        JuyitingConversationScope scope = authorize(request);

        assertEquals(List.of("agent-wuyong", "agent-linchong"), scope.targetAgentIds());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), scope.authoritativeAgentIds());
        assertEquals("[\"agent-wuyong\",\"agent-linchong\"]",
                service().serializeTargetAgentIds(scope.targetAgentIds()));
    }

    @Test
    void taskScopeWithoutTargetsDefaultsToAllAuthoritativeMembers() {
        when(agentService.listTaskWritableMemberAgentIds("tenant-a", "client-a", "372"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
        assertEquals(List.of("agent-wuyong", "agent-linchong"),
                authorize(bounty("372", List.of())).targetAgentIds());
    }

    @Test
    void privateTaskScopeAllowsOneAuthoritativeTarget() {
        when(agentService.listTaskWritableMemberAgentIds("tenant-a", "client-a", "372"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
        ChatMessageDTO request = bounty("372", List.of("agent-wuyong"));
        request.setConversationScopeType("private");
        request.setConversationScopeKey("task:372:agent:agent-wuyong");

        JuyitingConversationScope scope = authorize(request);

        assertEquals("agent-wuyong", scope.targetAgentId());
        assertEquals(List.of("agent-wuyong"), scope.targetAgentIds());
        assertEquals(List.of("agent-wuyong", "agent-linchong"),
                scope.authoritativeAgentIds());
    }

    @Test
    void privateScopeAllowsExactlyOneTarget() {
        when(agentService.listTaskWritableMemberAgentIds("tenant-a", "client-a", "372"))
                .thenReturn(List.of("agent-wuyong", "agent-linchong"));
        ChatMessageDTO request = bounty("372", List.of("agent-wuyong", "agent-linchong"));
        request.setConversationScopeType("private");
        request.setConversationScopeKey("task:372:agent:agent-wuyong");
        assertThrows(IllegalStateException.class, () -> authorize(request));
    }

    @Test
    void publicHallWithoutTaskDefaultsToBuiltinSongJiang() {
        when(builtinHallAgentSupport.defaultAgentId()).thenReturn("builtin-songjiang");
        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType("juyiting");
        JuyitingConversationScope scope = authorize(request);
        assertEquals("public", scope.scopeType());
        assertEquals(List.of("builtin-songjiang"), scope.targetAgentIds());
    }

    private JuyitingConversationScope authorize(ChatMessageDTO request) {
        JuyitingConversationScopeService service = service();
        return service.authorize(request, service.resolve(request), "tenant-a", "client-a");
    }

    private JuyitingConversationScopeService service() {
        return new JuyitingConversationScopeService(builtinHallAgentSupport, agentService);
    }

    private ChatMessageDTO bounty(String taskId, List<String> targets) {
        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType("juyiting");
        request.setConversationScopeType("bounty");
        request.setConversationScopeKey(taskId == null ? null : "task:" + taskId);
        request.setTaskId(taskId);
        request.setTargetAgentIds(targets);
        return request;
    }
}
