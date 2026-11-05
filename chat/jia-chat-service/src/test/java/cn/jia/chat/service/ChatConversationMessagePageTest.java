package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConversationMessagePageTest {
    private ChatConversationDao conversations;
    private ChatMessageDao messages;
    private ChatConversationService service;

    @BeforeEach void setup() {
        var context = new EsContext();
        context.setTenantId("tenant-a"); context.setJiacn("owner"); context.setClientId("client");
        EsContextHolder.setContext(context);
        conversations = mock(ChatConversationDao.class);
        messages = mock(ChatMessageDao.class);
        service = new ChatConversationServiceImpl(conversations, messages, mock(AgentTaskThreadDao.class),
                new ChatConversationEventBroker(), mock(ChatBountyBindingStore.class), mock(AgentService.class));
        var conversation = new ChatConversationEntity().setId(100L).setJiacn("owner").setLifecycleGeneration(1L);
        conversation.setTenantId("tenant-a"); conversation.setClientId("client");
        when(conversations.findScopedById("tenant-a", "owner", "client", "100")).thenReturn(conversation);
    }
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }
    private ChatMessageEntity message(long id) {
        var value = new ChatMessageEntity().setId(id);
        value.setCreateTime(1000L);
        return value;
    }
    @Test void boundedLatestUsesOneExtraRowAndReturnsChronologicalPage() {
        var rows = new ArrayList<ChatMessageEntity>();
        for (long id = 101; id >= 51; id--) rows.add(message(id));
        when(messages.findOwnedMessagePage("tenant-a", "owner", "client", "100", null, null, 51)).thenReturn(rows);
        var page = service.findMessagePage("100", null);
        assertEquals(50, page.messages().size());
        assertEquals(52L, page.messages().getFirst().getId());
        assertEquals(101L, page.messages().getLast().getId());
        assertTrue(page.hasMore()); assertEquals("1000:52", page.nextBefore());
        verify(messages).findOwnedMessagePage("tenant-a", "owner", "client", "100", null, null, 51);
        verifyNoMoreInteractions(messages);
    }
    @Test void olderPageUsesExactLongCursorAndStopsWithoutCountingHistory() {
        when(messages.findOwnedMessagePage("tenant-a", "owner", "client", "100", 1000L, 9007199254740993L, 51))
                .thenReturn(List.of(message(9), message(8)));
        var page = service.findMessagePage("100", "1000:9007199254740993");
        assertEquals(List.of(8L, 9L), page.messages().stream().map(ChatMessageEntity::getId).toList());
        assertFalse(page.hasMore()); assertNull(page.nextBefore());
    }
    @Test void emptyPageHasNoCursor() {
        when(messages.findOwnedMessagePage("tenant-a", "owner", "client", "100", null, null, 51)).thenReturn(List.of());
        assertEquals(new ChatConversationService.MessagePage(List.of(), false, null), service.findMessagePage("100", null));
    }
    @Test void nullableTimestampCursorDoesNotLoseHistoricalRows() {
        var rows = new ArrayList<ChatMessageEntity>();
        for (long id = 101; id >= 51; id--) rows.add(new ChatMessageEntity().setId(id));
        when(messages.findOwnedMessagePage("tenant-a", "owner", "client", "100", -1L, 102L, 51)).thenReturn(rows);
        var page = service.findMessagePage("100", "n:102");
        assertEquals("n:52", page.nextBefore()); assertTrue(page.hasMore());
    }
    @Test void invalidCursorFailsBeforeMessageQuery() {
        for (String cursor : List.of("", "1", "-1:1", "1:0", "01:1", "1:01", "1:2:3", "9223372036854775808:1", "1:9223372036854775808")) {
            assertThrows(ChatDeliberationException.class, () -> service.findMessagePage("100", cursor));
        }
        verifyNoInteractions(messages);
    }
    @Test void foreignAndMissingConversationsStillUseExistingAuthorization() {
        assertThrows(AgentTaskThreadException.class, () -> service.findMessagePage("999", null));
        var taskThread = new ChatConversationEntity().setId(200L).setJiacn("other-owner");
        taskThread.setTenantId("tenant-a"); taskThread.setClientId("client");
        when(conversations.findScopedById("tenant-a", "owner", "client", "200")).thenReturn(taskThread);
        assertThrows(AgentTaskThreadException.class, () -> service.findMessagePage("200", null));
        verifyNoInteractions(messages);
    }
}
