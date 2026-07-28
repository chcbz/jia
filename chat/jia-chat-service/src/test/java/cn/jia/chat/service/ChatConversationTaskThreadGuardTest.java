package cn.jia.chat.service;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatConversationTaskThreadGuardTest extends BaseMockTest {
    @Mock
    ChatConversationDao conversationDao;
    @Mock
    ChatMessageDao messageDao;
    @Mock
    AgentTaskThreadDao taskThreadDao;

    @Test
    void genericReadUpdateAndDeleteCannotBypassTaskThreadAcl() {
        ChatConversationEntity protectedConversation = new ChatConversationEntity();
        protectedConversation.setId(77L);
        protectedConversation.setConversationScopeType(
                AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        when(conversationDao.selectByEntity(any(ChatConversationEntity.class)))
                .thenReturn(List.of(protectedConversation));
        ChatConversationServiceImpl service =
                new ChatConversationServiceImpl(conversationDao, messageDao, taskThreadDao);

        assertThrows(AgentTaskThreadException.class, () -> service.get("77"));
        assertThrows(AgentTaskThreadException.class,
                () -> service.findByConversationId("77"));
        assertThrows(AgentTaskThreadException.class,
                () -> service.deleteConversation("77"));
        assertThrows(AgentTaskThreadException.class,
                () -> service.update(new ChatConversationEntity().setId(77L)));

        verify(messageDao, never()).findByConversationId("77");
        verify(messageDao, never()).deleteByConversationId("77");
        verify(conversationDao, never()).deleteById(77L);
        verify(conversationDao, never()).updateById(any());
    }
    @Test
    void bindingBlocksGenericApiEvenWhenConversationMarkerIsMissing() {
        ChatConversationEntity partiallyMigrated = new ChatConversationEntity();
        partiallyMigrated.setId(78L);
        when(conversationDao.selectByEntity(any(ChatConversationEntity.class)))
                .thenReturn(List.of(partiallyMigrated));
        when(taskThreadDao.findAnyByConversationId("78"))
                .thenReturn(new AgentTaskThreadEntity().setConversationId("78"));
        ChatConversationServiceImpl service =
                new ChatConversationServiceImpl(conversationDao, messageDao, taskThreadDao);

        assertThrows(AgentTaskThreadException.class, () -> service.get("78"));
        assertThrows(AgentTaskThreadException.class,
                () -> service.findByConversationId("78"));
        assertThrows(AgentTaskThreadException.class,
                () -> service.update(new ChatConversationEntity().setId(78L)));
        assertThrows(AgentTaskThreadException.class,
                () -> service.deleteConversation("78"));
    }

    @Test
    void corruptedCaseAndNulTaskMarkersFailClosedWithoutBinding() {
        ChatConversationServiceImpl service =
                new ChatConversationServiceImpl(conversationDao, messageDao, taskThreadDao);
        for (String marker : List.of("TASK_THREAD", "task_thread\0", " task_thread ")) {
            ChatConversationEntity corrupted = new ChatConversationEntity();
            corrupted.setId(79L);
            corrupted.setConversationScopeType(marker);
            when(conversationDao.selectByEntity(any(ChatConversationEntity.class)))
                    .thenReturn(List.of(corrupted));
            assertThrows(AgentTaskThreadException.class, () -> service.get("79"), marker);
        }
    }

}
