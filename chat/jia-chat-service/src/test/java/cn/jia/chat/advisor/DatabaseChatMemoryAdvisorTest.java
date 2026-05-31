package cn.jia.chat.advisor;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

class DatabaseChatMemoryAdvisorTest extends BaseMockTest {

    @Mock
    ChatMessageDao chatMessageDao;

    @Test
    void saveMessagePersistsJuyitingMetadata() throws Exception {
        DatabaseChatMemoryAdvisor advisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
                .conversationId("test-conv")
                .maxMessages(10)
                .build();

        // Access private saveMessage via reflection
        Method saveMessageMethod = DatabaseChatMemoryAdvisor.class.getDeclaredMethod(
                "saveMessage", org.springframework.ai.chat.messages.Message.class, Map.class);
        saveMessageMethod.setAccessible(true);

        Map<String, Object> context = new HashMap<>();
        context.put("jiacn", "test-user");
        context.put("clientId", "test-client");
        context.put("conversationType", "juyiting");
        context.put("senderType", "user");
        context.put("senderName", "宋江");

        UserMessage userMessage = UserMessage.builder().text("大家好").build();

        saveMessageMethod.invoke(advisor, userMessage, context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageDao).insert(captor.capture());

        ChatMessageEntity entity = captor.getValue();
        assertEquals("juyiting", entity.getConversationType());
        assertEquals("user", entity.getSenderType());
        assertEquals("宋江", entity.getSenderName());
        assertEquals("test-conv", entity.getConversationId());
        assertEquals("USER", entity.getMessageType());
        assertEquals("大家好", entity.getContent());
    }

    @Test
    void saveMessageDefaultsToNormalWhenConversationTypeMissing() throws Exception {
        DatabaseChatMemoryAdvisor advisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
                .conversationId("test-conv")
                .maxMessages(10)
                .build();

        Method saveMessageMethod = DatabaseChatMemoryAdvisor.class.getDeclaredMethod(
                "saveMessage", org.springframework.ai.chat.messages.Message.class, Map.class);
        saveMessageMethod.setAccessible(true);

        Map<String, Object> context = new HashMap<>();
        context.put("jiacn", "test-user");
        context.put("clientId", "test-client");
        // No conversationType, senderType, senderName

        AssistantMessage assistantMessage = new AssistantMessage("响应内容");

        saveMessageMethod.invoke(advisor, assistantMessage, context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageDao).insert(captor.capture());

        ChatMessageEntity entity = captor.getValue();
        assertEquals("normal", entity.getConversationType());
        assertEquals("", entity.getSenderType());
        assertEquals("", entity.getSenderName());
        assertEquals("ASSISTANT", entity.getMessageType());
    }

    @Test
    void saveMessageHandlesEmptySenderMetadataFromContext() throws Exception {
        DatabaseChatMemoryAdvisor advisor = DatabaseChatMemoryAdvisor.builder(chatMessageDao)
                .conversationId("test-conv")
                .maxMessages(10)
                .build();

        Method saveMessageMethod = DatabaseChatMemoryAdvisor.class.getDeclaredMethod(
                "saveMessage", org.springframework.ai.chat.messages.Message.class, Map.class);
        saveMessageMethod.setAccessible(true);

        Map<String, Object> context = new HashMap<>();
        context.put("jiacn", "test-user");
        context.put("clientId", "test-client");
        context.put("conversationType", "normal");
        context.put("senderType", "");   // empty senderType
        context.put("senderName", "");   // empty senderName

        UserMessage userMessage = UserMessage.builder().text("测试").build();

        saveMessageMethod.invoke(advisor, userMessage, context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageDao).insert(captor.capture());

        ChatMessageEntity entity = captor.getValue();
        assertEquals("normal", entity.getConversationType());
        assertEquals("", entity.getSenderType());
        assertEquals("", entity.getSenderName());
    }
}
