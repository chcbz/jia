package cn.jia.chat.service;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.chat.service.impl.LongTermMemoryServiceImpl;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryTaskThreadIsolationTest extends BaseMockTest {
    @Mock ChatMessageDao messageDao;
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
        verify(messageDao, never()).findByConversationId("77");
        verify(summaryGenerator, never()).summarizeConversation(org.mockito.ArgumentMatchers.any());
        verify(memoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
