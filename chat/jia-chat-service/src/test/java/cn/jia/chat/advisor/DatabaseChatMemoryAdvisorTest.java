package cn.jia.chat.advisor;

import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
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
        context.put(DatabaseChatMemoryAdvisor.USER_ADMISSION_SCOPE, areaZeroScope());
        context.put("conversationType", "juyiting");
        context.put("senderType", "user");
        context.put("senderName", "宋江");

        UserMessage userMessage = UserMessage.builder().text("大家好").build();

        saveMessageMethod.invoke(advisor, userMessage, context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq(areaZeroScope()), captor.capture());

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
        context.put(DatabaseChatMemoryAdvisor.USER_ADMISSION_SCOPE, areaZeroScope());
        // No conversationType, senderType, senderName

        AssistantMessage assistantMessage = new AssistantMessage("响应内容");

        saveMessageMethod.invoke(advisor, assistantMessage, context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq(areaZeroScope()), captor.capture());

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
        context.put(DatabaseChatMemoryAdvisor.USER_ADMISSION_SCOPE, areaZeroScope());
        context.put("conversationType", "normal");
        context.put("senderType", "");   // empty senderType
        context.put("senderName", "");   // empty senderName

        UserMessage userMessage = UserMessage.builder().text("测试").build();

        saveMessageMethod.invoke(advisor, userMessage, context);

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatConversationService).appendOwnedMessage(
                org.mockito.ArgumentMatchers.eq(areaZeroScope()), captor.capture());

        ChatMessageEntity entity = captor.getValue();
        assertEquals("normal", entity.getConversationType());
        assertEquals("", entity.getSenderType());
        assertEquals("", entity.getSenderName());
    }

    @Test
    void missingAdmissionScopeFailsBeforePersistence() throws Exception {
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

        java.lang.reflect.InvocationTargetException denied =
                org.junit.jupiter.api.Assertions.assertThrows(
                        java.lang.reflect.InvocationTargetException.class,
                        () -> saveMessageMethod.invoke(
                                advisor, UserMessage.builder().text("测试").build(), context));

        org.junit.jupiter.api.Assertions.assertTrue(denied.getCause() instanceof RuntimeException);
        org.junit.jupiter.api.Assertions.assertTrue(
                denied.getCause().getCause() instanceof IllegalStateException);
        verify(chatConversationService, org.mockito.Mockito.never())
                .appendOwnedMessage(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(ChatMessageEntity.class));
    }
    private UserAdmissionScope areaZeroScope() {
        return new UserAdmissionScope(
                new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"),
                        "test-user", "test-client"),
                1L, 0L, 0L, 0L, 0L);
    }
}
