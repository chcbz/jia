package cn.jia.chat.service;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatHallMessagePageServiceTest {
    @Test void combinedReadOwnsOneRepeatableReadSnapshotAndGenericPageDoesNotReadOutcomes() throws Exception {
        var transaction = ChatHallMessagePageService.class.getMethod("read", String.class, String.class)
                .getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly()); assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
        var conversations = mock(ChatConversationService.class);
        var requests = mock(ChatBountyRequestIndexService.class);
        var outcomes = mock(ChatMessageOutcomeReadService.class);
        var page = new ChatConversationService.MessagePage(List.of(), false, null);
        when(conversations.findMessagePage("42", null)).thenReturn(page);
        var result = new ChatHallMessagePageService(conversations, requests, outcomes).read("42", null);
        assertSame(page, result.messages()); assertNull(result.requestPage()); assertTrue(result.outcomeSummaries().isEmpty());
        verify(conversations).findMessagePage("42", null); verify(requests).messageWindow(page, true);
        verifyNoMoreInteractions(conversations, requests); verifyNoInteractions(outcomes);
    }
    @Test void olderWindowDoesNotRestoreLatestOrAcquireANewerEventCursor() {
        var conversations = mock(ChatConversationService.class);
        var requests = mock(ChatBountyRequestIndexService.class);
        var outcomes = mock(ChatMessageOutcomeReadService.class);
        var conversation = new ChatConversationEntity().setId(42L).setJiacn("owner").setLifecycleGeneration(1L);
        conversation.setTenantId("0"); conversation.setClientId("client");
        var page = new ChatConversationService.MessagePage(List.of(), false, null, conversation);
        var requestPage = new ChatBountyRequestIndexService.Page(1, new ChatBountyRequestIndexService.Scope("42", "1", "task"),
                "0", "0", null, false, List.of(), null);
        var scope = new ChatBountyRequestIndexStore.Scope("0", "owner", "client", "42", 1);
        when(conversations.findMessagePage("42", "1:2")).thenReturn(page);
        when(requests.messageWindow(page, false)).thenReturn(requestPage);
        when(outcomes.read(scope, List.of(), List.of())).thenReturn(List.of());
        when(requests.withDirectChildren(scope, requestPage, List.of())).thenReturn(requestPage);
        var result = new ChatHallMessagePageService(conversations, requests, outcomes).read("42", "1:2");
        assertNull(result.requestPage().eventCursor());
        verify(requests).messageWindow(page, false); verify(requests).withDirectChildren(scope, requestPage, List.of());
        verifyNoMoreInteractions(requests);
    }
}
