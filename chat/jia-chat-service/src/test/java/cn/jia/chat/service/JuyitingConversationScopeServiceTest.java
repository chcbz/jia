package cn.jia.chat.service;

import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class JuyitingConversationScopeServiceTest extends BaseMockTest {
    @Mock
    BuiltinHallAgentSupport builtinHallAgentSupport;

    @Test
    void resolvesRequestFieldsBeforeMetadataFallbacks() {
        JuyitingConversationScopeService service = new JuyitingConversationScopeService(builtinHallAgentSupport);

        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType("juyiting");
        request.setConversationScopeType("bounty");
        request.setConversationScopeKey("task:372");
        request.setTaskId("372");
        request.setTargetAgentId("agent-wuyong");
        request.setTargetAgentIds(List.of("agent-wuyong", "agent-wuyong", "agent-linchong"));
        request.setMetadata(Map.of(
                "mode", "public",
                "scopeKey", "public",
                "selectedTaskId", "999",
                "targetAgentIds", List.of("agent-songjiang")
        ));

        JuyitingConversationScope scope = service.resolve(request);

        assertEquals("bounty", scope.scopeType());
        assertEquals("task:372", scope.scopeKey());
        assertEquals("372", scope.taskId());
        assertEquals("agent-wuyong", scope.targetAgentId());
        assertEquals(List.of("agent-wuyong", "agent-linchong"), scope.targetAgentIds());
    }

    @Test
    void derivesPrivateScopeFromTaskAndTarget() {
        JuyitingConversationScopeService service = new JuyitingConversationScopeService(builtinHallAgentSupport);

        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType("juyiting");
        request.setConversationScopeType("private");
        request.setTaskId("task-7");
        request.setMetadata(Map.of("selectedAgentId", "agent-linchong"));

        JuyitingConversationScope scope = service.resolve(request);

        assertEquals("private", scope.scopeType());
        assertEquals("task:task-7:agent:agent-linchong", scope.scopeKey());
        assertEquals("task-7", scope.taskId());
        assertEquals("agent-linchong", scope.targetAgentId());
        assertEquals(List.of("agent-linchong"), scope.targetAgentIds());
        assertEquals("agent-linchong", service.targetAgentIdFromScopeKey(scope.scopeKey()));
    }

    @Test
    void defaultsPublicHallScopeToBuiltinSongJiang() {
        when(builtinHallAgentSupport.defaultAgentId()).thenReturn("builtin-songjiang");
        JuyitingConversationScopeService service = new JuyitingConversationScopeService(builtinHallAgentSupport);

        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType("juyiting");

        JuyitingConversationScope scope = service.resolve(request);

        assertEquals("public", scope.scopeType());
        assertEquals("public", scope.scopeKey());
        assertEquals("builtin-songjiang", scope.targetAgentId());
        assertEquals(List.of("builtin-songjiang"), scope.targetAgentIds());
    }

    @Test
    void rejectsBountyTargetOutsideParticipants() {
        JuyitingConversationScopeService service = new JuyitingConversationScopeService(builtinHallAgentSupport);

        ChatMessageDTO request = new ChatMessageDTO();
        request.setConversationType("juyiting");
        request.setConversationScopeType("bounty");
        request.setTaskId("372");
        request.setTargetAgentIds(List.of("agent-linchong"));
        request.setMetadata(Map.of("participantAgentIds", List.of("agent-wuyong")));

        JuyitingConversationScope scope = service.resolve(request);
        Optional<String> invalid = service.invalidBountyTarget(request, scope);

        assertEquals(Optional.of("agent-linchong"), invalid);
    }
}
