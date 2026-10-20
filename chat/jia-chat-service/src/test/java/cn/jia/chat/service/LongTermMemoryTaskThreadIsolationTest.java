package cn.jia.chat.service;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.job.MemoryJobHandler;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.chat.service.impl.LongTermMemoryServiceImpl;
import cn.jia.core.lock.IDistributedLock;
import cn.jia.core.lock.ILock;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LongTermMemoryTaskThreadIsolationTest extends BaseMockTest {
    @Mock ChatMessageDao messageDao;
    @Mock ChatConversationDao parentDao;
    @Mock ChatConversationService conversationService;
    @Mock SummaryGenerator summaryGenerator;
    @Mock MemoryRepository memoryRepository;
    @Mock AgentTaskThreadMemoryGuard memoryGuard;
    @Mock IDistributedLock locks;
    @Mock PlatformTransactionManager transactions;
    LongTermMemoryServiceImpl service;

    @BeforeEach void setup() {
        service = new LongTermMemoryServiceImpl();
        ReflectionTestUtils.setField(service, "chatMessageDao", messageDao);
        ReflectionTestUtils.setField(service, "chatConversationDao", parentDao);
        ReflectionTestUtils.setField(service, "chatConversationService", conversationService);
        ReflectionTestUtils.setField(service, "summaryGenerator", summaryGenerator);
        ReflectionTestUtils.setField(service, "memoryRepository", memoryRepository);
        ReflectionTestUtils.setField(service, "taskThreadMemoryGuard", memoryGuard);
        ReflectionTestUtils.setField(service, "distributedLock", locks);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        ReflectionTestUtils.setField(service, "batchSize", 10);
    }

    ChatConversationEntity parent() {
        ChatConversationEntity p = new ChatConversationEntity().setId(88L).setJiacn("owner-a")
                .setLifecycleGeneration(0L);
        p.setClientId("client-a"); p.setTenantId("0");
        return p;
    }

    ChatMessageEntity message(long id, String type) {
        ChatMessageEntity m = new ChatMessageEntity().setId(id).setConversationId("88")
                .setJiacn("owner-a").setContent("owned").setMessageType(type).setSyncStatus("PENDING");
        m.setClientId("client-a"); m.setTenantId("0");
        return m;
    }

    void source(List<ChatMessageEntity> messages) {
        when(locks.lock("chat:memory:conversation:88")).thenReturn(new ILock("conversation-lock", locks));
        when(parentDao.findScopedById("owner-a", "client-a", "88")).thenReturn(parent());
        when(conversationService.findOwnedMessages("owner-a", "client-a", "88")).thenReturn(messages);
    }

    TransactionStatus commitReady() {
        when(summaryGenerator.summarizeConversation(anyList())).thenReturn("summary");
        when(memoryRepository.generateEmbedding("summary")).thenReturn(new float[]{1, 0, 0});
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(parentDao.lockScopedById("owner-a", "client-a", "88")).thenReturn(parent());
        return status;
    }

    @Test void taskThreadIsExcludedWithoutReadingContent() {
        when(locks.lock("chat:memory:conversation:77")).thenReturn(new ILock("task-lock", locks));
        when(memoryGuard.isProtectedConversation("77")).thenReturn(true);
        service.syncConversation("77");
        verify(messageDao).updateSyncStatusByConversationId("77", AgentTaskThreadConstants.MEMORY_SYNC_EXCLUDED);
        verifyNoInteractions(parentDao, summaryGenerator, memoryRepository, conversationService);
        verify(locks).unLock("task-lock");
    }

    @Test void syncMarksOnlySnapshotAndExcludesSystemMessages() {
        ChatMessageEntity user = message(1, "USER");
        source(List.of(user, message(2, "SYSTEM")));
        TransactionStatus status = commitReady();
        when(messageDao.markMemorySnapshotSynced("owner-a", "client-a", "88", List.of(1L))).thenReturn(1);
        service.syncOwnedConversation("owner-a", "client-a", "88");
        verify(summaryGenerator).summarizeConversation(List.of(user));
        verify(messageDao).markMemorySnapshotSynced("owner-a", "client-a", "88", List.of(1L));
        verify(messageDao, never()).updateSyncStatusByConversationId(any(), any());
        verify(messageDao, never()).findByConversationIdForMaintenance(any());
        verify(transactions).commit(status);
        var order = inOrder(memoryRepository, parentDao);
        order.verify(memoryRepository).generateEmbedding("summary");
        order.verify(parentDao).lockScopedById("owner-a", "client-a", "88");
        order.verify(memoryRepository).savePrepared(any());
        verify(locks).unLock("conversation-lock");
    }

    @Test void retryAfterDatabaseFailureUsesSameDocumentId() {
        source(List.of(message(1, "USER")));
        TransactionStatus status = commitReady();
        when(messageDao.markMemorySnapshotSynced("owner-a", "client-a", "88", List.of(1L)))
                .thenThrow(new IllegalStateException("database unavailable")).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.syncOwnedConversation("owner-a", "client-a", "88"));
        service.syncOwnedConversation("owner-a", "client-a", "88");
        var docs = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository, times(2)).savePrepared(docs.capture());
        assertEquals(docs.getAllValues().get(0).getId(), docs.getAllValues().get(1).getId());
        assertEquals("owner-a", docs.getValue().getJiacn());
        verify(transactions).rollback(status);
        verify(transactions).commit(status);
    }

    @Test void deletionDuringModelCallPreventsIndexWrite() {
        source(List.of(message(1, "USER")));
        TransactionStatus status = commitReady();
        when(parentDao.lockScopedById("owner-a", "client-a", "88")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.syncOwnedConversation("owner-a", "client-a", "88"));
        verify(memoryRepository, never()).savePrepared(any());
        verify(messageDao, never()).markMemorySnapshotSynced(any(), any(), any(), anyList());
        verify(transactions).rollback(status);
    }

    @Test void foreignMessageFailsBeforeModels() {
        source(List.of(message(1, "USER").setJiacn("other-owner")));
        assertThrows(IllegalStateException.class, () -> service.syncOwnedConversation("owner-a", "client-a", "88"));
        verifyNoInteractions(summaryGenerator, memoryRepository, transactions);
    }

    @Test void emptySnapshotDoesNotMarkConcurrentNewMessagesSynced() {
        source(List.of());
        service.syncOwnedConversation("owner-a", "client-a", "88");
        verifyNoInteractions(messageDao, summaryGenerator, memoryRepository, transactions);
    }

    @Test void embeddingFailureLeavesPendingAndReleasesLock() {
        source(List.of(message(1, "USER")));
        when(summaryGenerator.summarizeConversation(anyList())).thenReturn("summary");
        when(memoryRepository.generateEmbedding("summary")).thenThrow(new IllegalStateException("provider unavailable"));
        assertThrows(IllegalStateException.class, () -> service.syncOwnedConversation("owner-a", "client-a", "88"));
        verifyNoInteractions(transactions, messageDao);
        verify(memoryRepository, never()).savePrepared(any());
        verify(locks).unLock("conversation-lock");
    }

    @Test void indexFailureNeverMarksSnapshotSynced() {
        source(List.of(message(1, "USER")));
        TransactionStatus status = commitReady();
        doThrow(new IllegalStateException("index unavailable")).when(memoryRepository).savePrepared(any());
        assertThrows(IllegalStateException.class, () -> service.syncOwnedConversation("owner-a", "client-a", "88"));
        verifyNoInteractions(messageDao);
        verify(transactions).rollback(status);
    }

    @Test void failedConversationDoesNotStarveLaterPagesOrRetryForever() {
        LongTermMemoryServiceImpl spy = spy(service);
        when(messageDao.findPendingConversationIds("PENDING", 10, 0)).thenReturn(List.of("88"));
        when(messageDao.findPendingConversationIds("PENDING", 10, 88)).thenReturn(List.of("99"));
        when(messageDao.findPendingConversationIds("PENDING", 10, 99)).thenReturn(List.of());
        doThrow(new IllegalStateException("provider unavailable")).when(spy).syncConversation("88");
        doNothing().when(spy).syncConversation("99");
        assertThrows(IllegalStateException.class, spy::syncPendingConversations);
        verify(spy, times(1)).syncConversation("88");
        verify(spy, times(1)).syncConversation("99");
    }

    @Test void scheduledAndManualEntryShareLockAndPropagateFailure() throws Exception {
        MemoryJobHandler handler = new MemoryJobHandler();
        LongTermMemoryService sync = mock(LongTermMemoryService.class);
        ReflectionTestUtils.setField(handler, "longTermMemoryService", sync);
        ReflectionTestUtils.setField(handler, "distributedLock", locks);
        ReflectionTestUtils.setField(handler, "taskExecutor", (java.util.concurrent.Executor) Runnable::run);
        when(locks.tryLock("chat:memory:sync-job", 0)).thenReturn(new ILock("job-lock", locks));
        doThrow(new IllegalStateException("index unavailable")).when(sync).syncPendingConversations();
        handler.scheduledSync();
        assertThrows(IllegalStateException.class, () -> handler.execute(new cn.jia.task.job.JobContext()));
        verify(locks, times(2)).unLock("job-lock");
        assertFalse(handler.isParallel());
        assertNotNull(MemoryJobHandler.class.getMethod("scheduledSync").getAnnotation(Scheduled.class));
        when(locks.tryLock("chat:memory:sync-job", 0)).thenReturn(null);
        assertTrue(handler.execute(new cn.jia.task.job.JobContext()).startsWith("SKIPPED:"));
        verify(sync, times(2)).syncPendingConversations();
    }
}
