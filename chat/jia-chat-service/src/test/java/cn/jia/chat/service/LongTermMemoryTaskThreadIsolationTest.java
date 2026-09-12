package cn.jia.chat.service;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.chat.service.impl.LongTermMemoryServiceImpl;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryTaskThreadIsolationTest extends BaseMockTest {
    @Mock ChatMessageDao messageDao;
    @Mock ChatConversationService conversationService;
    @Mock SummaryGenerator summaryGenerator;
    @Mock MemoryRepository memoryRepository;
    @Mock AgentTaskThreadMemoryGuard memoryGuard;

    @Test
    void taskThreadConversationIsMarkedExcludedBeforeAnyContentReadOrVectorWrite() throws Exception {
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl();
        set(service, "chatMessageDao", messageDao);
        set(service, "summaryGenerator", summaryGenerator);
        set(service, "memoryRepository", memoryRepository);
        set(service, "taskThreadMemoryGuard", memoryGuard);
        when(memoryGuard.isProtectedConversation("77")).thenReturn(true);

        service.syncConversation("77");

        verify(messageDao).updateSyncStatusByConversationId(
                "77", AgentTaskThreadConstants.MEMORY_SYNC_EXCLUDED);
        verify(messageDao, never()).findByConversationIdForMaintenance("77");
        verify(summaryGenerator, never()).summarizeConversation(org.mockito.ArgumentMatchers.any());
        verify(memoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void authenticatedSyncReadsOnlyTheCapturedOwnedConversation() throws Exception {
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl();
        set(service, "chatMessageDao", messageDao);
        set(service, "chatConversationService", conversationService);
        set(service, "summaryGenerator", summaryGenerator);
        set(service, "memoryRepository", memoryRepository);
        ChatMessageEntity message = new ChatMessageEntity()
                .setConversationId("88").setJiacn("owner-a").setContent("owned");
        message.setClientId("client-a");
        message.setTenantId("0");
        when(conversationService.findOwnedMessages("owner-a", "client-a", "88"))
                .thenReturn(List.of(message));
        when(summaryGenerator.summarizeConversation(List.of(message))).thenReturn("summary");

        service.syncOwnedConversation("owner-a", "client-a", "88");

        verify(conversationService).findOwnedMessages("owner-a", "client-a", "88");
        verify(messageDao, never()).findByConversationIdForMaintenance(any());
        org.mockito.ArgumentCaptor<MemoryDocument> document =
                org.mockito.ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository).save(document.capture());
        assertEquals("owner-a", document.getValue().getJiacn());
        assertEquals("88", document.getValue().getConversationId());
        assertEquals("summary", document.getValue().getContent());
        verify(messageDao).updateSyncStatusByConversationId(eq("88"), eq("SYNCED"));
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
