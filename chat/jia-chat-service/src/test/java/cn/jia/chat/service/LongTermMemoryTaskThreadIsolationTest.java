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
        ReflectionTestUtils.setField(service, "processScope", new cn.jia.core.security.FixedTenantAdmissionPolicy("tenant-a", null));
        ReflectionTestUtils.setField(service, "zone", "Asia/Shanghai");
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
        p.setClientId("client-a"); p.setTenantId("tenant-a");
        return p;
    }

    ChatMessageEntity message(long id, String type) {
        ChatMessageEntity m = new ChatMessageEntity().setId(id).setConversationId("88")
                .setJiacn("owner-a").setContent("owned").setMessageType(type).setSyncStatus("PENDING");
        m.setClientId("client-a"); m.setTenantId("tenant-a");
        return m;
    }

    void source(List<ChatMessageEntity> messages) {
        when(locks.lock("chat:memory:conversation:88")).thenReturn(new ILock("conversation-lock", locks));
        when(parentDao.findScopedById("tenant-a", "owner-a", "client-a", "88")).thenReturn(parent());
        when(conversationService.findOwnedMessages("tenant-a", "owner-a", "client-a", "88")).thenReturn(messages);
    }

    TransactionStatus commitReady() {
        when(summaryGenerator.summarizeConversation(anyList())).thenReturn("summary");
        when(memoryRepository.generateEmbedding("summary")).thenReturn(new float[]{1, 0, 0});
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(parentDao.lockScopedById("tenant-a", "owner-a", "client-a", "88")).thenReturn(parent());
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
        when(messageDao.markMemorySnapshotSynced("tenant-a", "owner-a", "client-a", "88", List.of(1L))).thenReturn(1);
        service.syncOwnedConversation("owner-a", "client-a", "88");
        verify(summaryGenerator).summarizeConversation(List.of(user));
        var saved = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository).savePrepared(saved.capture());
        assertEquals("client-a", saved.getValue().getClientId());
        verify(messageDao).markMemorySnapshotSynced("tenant-a", "owner-a", "client-a", "88", List.of(1L));
        verify(messageDao, never()).updateSyncStatusByConversationId(any(), any());
        verify(messageDao, never()).findByConversationIdForMaintenance(any());
        verify(transactions).commit(status);
        var order = inOrder(memoryRepository, parentDao);
        order.verify(memoryRepository).generateEmbedding("summary");
        order.verify(parentDao).lockScopedById("tenant-a", "owner-a", "client-a", "88");
        order.verify(memoryRepository).savePrepared(any());
        verify(locks).unLock("conversation-lock");
    }

    @Test void retryAfterDatabaseFailureUsesSameDocumentId() {
        source(List.of(message(1, "USER")));
        TransactionStatus status = commitReady();
        when(messageDao.markMemorySnapshotSynced("tenant-a", "owner-a", "client-a", "88", List.of(1L)))
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
        when(parentDao.lockScopedById("tenant-a", "owner-a", "client-a", "88")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.syncOwnedConversation("owner-a", "client-a", "88"));
        verify(memoryRepository, never()).savePrepared(any());
        verify(messageDao, never()).markMemorySnapshotSynced(any(), any(), any(), any(), anyList());
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

    @Test void manualEntryKeepsLockAndPropagatesFailureWithoutLocalTimer() throws Exception {
        MemoryJobHandler handler = new MemoryJobHandler();
        LongTermMemoryService sync = mock(LongTermMemoryService.class);
        ReflectionTestUtils.setField(handler, "longTermMemoryService", sync);
        ReflectionTestUtils.setField(handler, "distributedLock", locks);
        when(locks.tryLock("chat:memory:sync-job", 0)).thenReturn(new ILock("job-lock", locks));
        doThrow(new IllegalStateException("index unavailable")).when(sync).syncPendingConversations();
        assertThrows(IllegalStateException.class, () -> handler.execute(new cn.jia.task.job.JobContext()));
        verify(locks).unLock("job-lock");
        assertFalse(handler.isParallel());
        for (var method : MemoryJobHandler.class.getDeclaredMethods())
            assertFalse(method.isAnnotationPresent(Scheduled.class));
        when(locks.tryLock("chat:memory:sync-job", 0)).thenReturn(null);
        assertTrue(handler.execute(new cn.jia.task.job.JobContext()).startsWith("SKIPPED:"));
        verify(sync).syncPendingConversations();
    }
    @Test void periodicSummariesKeepClientPairsAndDistinctDocumentIds() {
        var owners = List.of(new cn.jia.chat.memory.MemoryOwner("client-a", "owner-a"),
                new cn.jia.chat.memory.MemoryOwner("client-b", "owner-a"));
        when(messageDao.findActiveMemoryOwners(anyLong())).thenReturn(owners);
        when(memoryRepository.findConversationSummariesByJiacn(anyString(), eq("owner-a"), anyLong())).thenReturn(List.of("conversation"));
        when(memoryRepository.findDailySummariesByJiacn(anyString(), eq("owner-a"), anyLong())).thenReturn(List.of("daily"));
        when(memoryRepository.findWeeklySummariesByJiacn(anyString(), eq("owner-a"), anyLong())).thenReturn(List.of("weekly"));
        when(summaryGenerator.generateDailySummary(anyList(), eq("owner-a"), anyString())).thenReturn("daily result");
        when(summaryGenerator.generateWeeklySummary(anyList(), eq("owner-a"), anyString())).thenReturn("weekly result");
        when(summaryGenerator.generateMonthlySummary(anyList(), eq("owner-a"), anyString())).thenReturn("monthly result");
        service.generateDailySummary(); service.generateWeeklySummary(); service.generateMonthlySummary();
        var documents = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository, times(6)).save(documents.capture());
        assertEquals(6, documents.getAllValues().stream().map(MemoryDocument::getId).distinct().count());
        for (String client : List.of("client-a", "client-b")) {
            verify(memoryRepository).findConversationSummariesByJiacn(eq(client), eq("owner-a"), anyLong());
            verify(memoryRepository).findDailySummariesByJiacn(eq(client), eq("owner-a"), anyLong());
            verify(memoryRepository).findWeeklySummariesByJiacn(eq(client), eq("owner-a"), anyLong());
            assertEquals(3, documents.getAllValues().stream().filter(d -> client.equals(d.getClientId())).count());
        }
    }

    @Test void wrongConfiguredClientFailsBeforeSourceOrPaidWork() {
        ReflectionTestUtils.setField(service, "processScope", new cn.jia.core.security.FixedTenantAdmissionPolicy("tenant-a", "only-client"));
        when(locks.lock("chat:memory:conversation:88")).thenReturn(new ILock("conversation-lock", locks));
        assertThrows(IllegalArgumentException.class, () -> service.syncOwnedConversation("owner-a", "client-a", "88"));
        verifyNoInteractions(parentDao, conversationService, summaryGenerator, memoryRepository);
    }

}
