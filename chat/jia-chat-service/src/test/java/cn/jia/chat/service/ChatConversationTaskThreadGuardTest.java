package cn.jia.chat.service;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
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

    @Test
    void genericReadUpdateAndDeleteCannotBypassTaskThreadAcl() {
        ChatConversationEntity protectedConversation = new ChatConversationEntity();
        protectedConversation.setId(77L);
        protectedConversation.setConversationScopeType(
                AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        when(conversationDao.selectByEntity(any(ChatConversationEntity.class)))
                .thenReturn(List.of(protectedConversation));
        ChatConversationServiceImpl service =
                new ChatConversationServiceImpl(conversationDao, messageDao);

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
}
