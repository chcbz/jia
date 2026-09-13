package cn.jia.chat.service.impl;

import cn.jia.chat.entity.AgentTaskThreadDTO;
import cn.jia.chat.entity.AgentTaskThreadMessageDTO;
import cn.jia.chat.service.AgentTaskThreadService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskContextPackConversationSourceImplTest {
    @Test
    void returnsOnlyBoundedMetadataAndNeverExposesMessageContentOrMetadata() {
        AgentTaskThreadService threadService = mock(AgentTaskThreadService.class);
        AgentTaskContextPackConversationSourceImpl source =
                new AgentTaskContextPackConversationSourceImpl(threadService);
        AgentTaskThreadDTO thread = new AgentTaskThreadDTO().setConversationId("31");
        when(threadService.getTeamThread("tenant", "client", "task", "actor"))
                .thenReturn(thread);
        List<AgentTaskThreadMessageDTO> messages = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            messages.add(new AgentTaskThreadMessageDTO()
                    .setConversationId("31")
                    .setCreatedAt((long) index + 1)
                    .setContent("Authorization: Bearer raw-secret-" + index)
                    .setMetadata("{\"password\":\"raw-secret\"}"));
        }
        when(threadService.listTeamMessages("tenant", "client", "task", "actor", 101))
                .thenReturn(messages);

        var result = source.findReference("tenant", "client", "task", "actor");

        assertTrue(result.available());
        assertTrue(result.truncated());
        assertEquals("31", result.conversationId());
        assertEquals(100, result.recentMessageCount());
        assertEquals(101L, result.latestMessageAt());
        assertFalse(result.toString().contains("raw-secret"));
        verify(threadService).getTeamThread("tenant", "client", "task", "actor");
        verify(threadService).listTeamMessages("tenant", "client", "task", "actor", 101);
    }

    @Test
    void absentOrContaminatedThreadIsExplicitlyUnavailable() {
        AgentTaskThreadService threadService = mock(AgentTaskThreadService.class);
        AgentTaskContextPackConversationSourceImpl source =
                new AgentTaskContextPackConversationSourceImpl(threadService);
        when(threadService.getTeamThread("tenant", "client", "task", "actor"))
                .thenReturn(new AgentTaskThreadDTO().setConversationId("31"));
        when(threadService.listTeamMessages("tenant", "client", "task", "actor", 101))
                .thenReturn(List.of(new AgentTaskThreadMessageDTO()
                        .setConversationId("foreign").setCreatedAt(1L)));

        var result = source.findReference("tenant", "client", "task", "actor");

        assertFalse(result.available());
        assertEquals("CONVERSATION_REFERENCE_INVALID", result.reason());
    }

    @Test
    void implementationHasOnlyThreadServiceDependencyNotAiOrSummaryProvider() {
        assertEquals(List.of(AgentTaskThreadService.class),
                java.util.Arrays.stream(
                                AgentTaskContextPackConversationSourceImpl.class.getDeclaredFields())
                        .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                        .map(java.lang.reflect.Field::getType).toList());
    }
}
