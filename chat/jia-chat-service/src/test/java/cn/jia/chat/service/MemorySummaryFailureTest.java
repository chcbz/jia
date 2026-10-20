package cn.jia.chat.service;

import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.impl.DefaultSummaryGenerator;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class MemorySummaryFailureTest extends BaseMockTest {
    @Test void providerFailureAndInvalidSummaryMustNotBecomeSuccessfulMemory() {
        ChatModel model = mock(ChatModel.class);
        DefaultSummaryGenerator generator = new DefaultSummaryGenerator();
        ReflectionTestUtils.setField(generator, "chatModel", model);
        var messages = List.of(new ChatMessageEntity().setMessageType("USER").setContent("test"));
        List<Runnable> calls = List.of(() -> generator.summarizeConversation(messages),
                () -> generator.generateDailySummary(List.of("summary"), "owner", "2026-10-20"),
                () -> generator.generateWeeklySummary(List.of("summary"), "owner", "2026-10-19"),
                () -> generator.generateMonthlySummary(List.of("summary"), "owner", "2026-10"));
        when(model.call(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        for (Runnable call : calls) assertThrows(IllegalStateException.class, call::run);
        for (String invalid : List.of("", " ", "{}", "not JSON", "{\"summary\":\"\"}", "{\"summary\":3}")) {
            doReturn(invalid).when(model).call(anyString());
            for (Runnable call : calls) assertThrows(IllegalStateException.class, call::run);
        }
    }

    @Test void validSummaryIsPreserved() {
        ChatModel model = mock(ChatModel.class);
        DefaultSummaryGenerator generator = new DefaultSummaryGenerator();
        ReflectionTestUtils.setField(generator, "chatModel", model);
        String content = "{\"summary\":\"备用钥匙在蓝色柜子\"}";
        when(model.call(anyString())).thenReturn(content);
        assertEquals(content, generator.summarizeConversation(List.of(
                new ChatMessageEntity().setMessageType("USER").setContent("test"))));
    }
}
