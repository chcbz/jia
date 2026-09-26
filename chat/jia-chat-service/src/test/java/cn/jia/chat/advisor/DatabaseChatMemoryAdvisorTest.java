package cn.jia.chat.advisor;

import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.DisplayNameSource;
import cn.jia.chat.service.ServerResolvedSender;
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
    ChatConversationService chatConversationService;

    @Test
    void saveMessagePersistsJuyitingMetadata() throws Exception {
        DatabaseChatMemoryAdvisor advisor = DatabaseChatMemoryAdvisor.builder(chatConversationService)
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
        context.put("senderType", "agent");
        context.put("senderName", "宋江");
        context.put(DatabaseChatMemoryAdvisor.SERVER_RESOLVED_SENDER,
                new ServerResolvedSender("user", "测试用户", "test-user", "test-client",
                        DisplayNameSource.NICKNAME));

        UserMessage userMessage = UserMessage.builder().text("大家好").build();

        saveMessageMethod.invoke(advisor, userMessage, context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq("test-user"),
                org.mockito.ArgumentMatchers.eq("test-client"), captor.capture());

        ChatMessageEntity entity = captor.getValue();
        assertEquals("juyiting", entity.getConversationType());
        assertEquals("user", entity.getSenderType());
        assertEquals("测试用户", entity.getSenderName());
        assertEquals("test-conv", entity.getConversationId());
        org.junit.jupiter.api.Assertions.assertTrue(entity.getMetadata().contains("\"senderType\":\"user\""));
        org.junit.jupiter.api.Assertions.assertTrue(entity.getMetadata().contains("\"senderName\":\"测试用户\""));
        org.junit.jupiter.api.Assertions.assertTrue(entity.getMetadata().contains("\"jiacn\":\"test-user\""));
        assertEquals("USER", entity.getMessageType());
        assertEquals("大家好", entity.getContent());
    }

    @Test
    void assistantPersistenceNeverReusesTheAuthenticatedHumanDisplayName() throws Exception {
        DatabaseChatMemoryAdvisor advisor = DatabaseChatMemoryAdvisor.builder(chatConversationService)
                .conversationId("test-conv")
                .maxMessages(10)
                .build();
        Method saveMessageMethod = DatabaseChatMemoryAdvisor.class.getDeclaredMethod(
                "saveMessage", org.springframework.ai.chat.messages.Message.class, Map.class);
        saveMessageMethod.setAccessible(true);

        Map<String, Object> context = new HashMap<>();
        context.put("jiacn", "test-user");
        context.put("clientId", "test-client");
        context.put(DatabaseChatMemoryAdvisor.SERVER_RESOLVED_SENDER,
                new ServerResolvedSender("user", "陈惠超", "test-user", "test-client",
                        DisplayNameSource.NICKNAME));

        saveMessageMethod.invoke(advisor, new AssistantMessage("响应内容"), context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq("test-user"),
                org.mockito.ArgumentMatchers.eq("test-client"), captor.capture());
        ChatMessageEntity entity = captor.getValue();
        assertEquals("ASSISTANT", entity.getMessageType());
        assertEquals("assistant", entity.getSenderType());
        assertEquals("助手", entity.getSenderName());
        org.junit.jupiter.api.Assertions.assertFalse(entity.getMetadata().contains("陈惠超"));
    }

    @Test
    void saveMessageDefaultsToNormalWhenConversationTypeMissing() throws Exception {
        DatabaseChatMemoryAdvisor advisor = DatabaseChatMemoryAdvisor.builder(chatConversationService)
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
        verify(chatConversationService).appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq("test-user"),
                org.mockito.ArgumentMatchers.eq("test-client"), captor.capture());

        ChatMessageEntity entity = captor.getValue();
        assertEquals("normal", entity.getConversationType());
        assertEquals("", entity.getSenderType());
        assertEquals("", entity.getSenderName());
        assertEquals("ASSISTANT", entity.getMessageType());
    }

    @Test
    void saveMessageHandlesEmptySenderMetadataFromContext() throws Exception {
        DatabaseChatMemoryAdvisor advisor = DatabaseChatMemoryAdvisor.builder(chatConversationService)
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
        verify(chatConversationService).appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq("test-user"),
                org.mockito.ArgumentMatchers.eq("test-client"), captor.capture());

        ChatMessageEntity entity = captor.getValue();
        assertEquals("normal", entity.getConversationType());
        assertEquals("", entity.getSenderType());
        assertEquals("", entity.getSenderName());
    }
}
