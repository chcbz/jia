package cn.jia.chat.service;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.MemoryPublicationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationPayload;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.entity.MemoryWorkItem;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.memory.MemoryRepositoryImpl;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.chat.service.impl.LongTermMemoryServiceImpl;
import cn.jia.chat.service.impl.MemoryPublicationCommitService;
import cn.jia.test.BaseMockTest;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.embedding.EmbeddingModel;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryTaskThreadIsolationTest extends BaseMockTest {
    @Mock ChatMessageDao messageDao;
    @Mock ChatConversationService conversationService;
    @Mock SummaryGenerator summaryGenerator;
    @Mock MemoryRepository memoryRepository;
    @Mock AgentTaskThreadMemoryGuard memoryGuard;
    @Mock MemoryPublicationDao publicationDao;
    @Mock MemoryPublicationCommitService publicationCommitService;

    @Test
    void unscopedConversationSyncFailsClosedWithoutReadingOrMutatingMessages() throws Exception {
        LongTermMemoryServiceImpl service = service();

        assertThrows(IllegalStateException.class, () -> service.syncConversation("77"));

        verify(messageDao, never()).findByConversationIdForMaintenance(anyString());
        verify(messageDao, never()).updateSyncStatusByConversationId(anyString(), anyString());
        verify(memoryRepository, never()).save(any(MemoryDocument.class));
    }

    @Test
    void authenticatedSyncPublishesExactLegacyScopeAndOnlyCapturedPendingIds() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity pending = message(101L, "88", "owned", "PENDING", "0", "cyf", "client-a");
        ChatMessageEntity synced = message(102L, "88", "history", "SYNCED", "0", "cyf", "client-a");
        stubOwnedConversation("88", List.of(pending, synced));
        when(summaryGenerator.summarizeConversation(List.of(pending, synced))).thenReturn("summary");

        service.syncOwnedConversation("owner-a", "client-a", "88");

        ArgumentCaptor<ConversationDataScope> scope = ArgumentCaptor.forClass(ConversationDataScope.class);
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(publicationDao).stage(scope.capture(), eq("88"), eq(7L), token.capture(),
                anyString(), eq(2), anyLong());
        assertEquals(ConversationDataScope.Mode.LEGACY_CLIENT, scope.getValue().mode());
        assertEquals("0", scope.getValue().tenantId());
        assertEquals("cyf", scope.getValue().dataDomainId());
        assertEquals("owner-a", scope.getValue().ownerJiacn());
        assertEquals("client-a", scope.getValue().callerClientId());

        ArgumentCaptor<MemoryDocument> document = ArgumentCaptor.forClass(MemoryDocument.class);
        ArgumentCaptor<MemoryPublicationRecord> publication =
                ArgumentCaptor.forClass(MemoryPublicationRecord.class);
        verify(memoryRepository).saveConversationSnapshot(
                eq(scope.getValue()), document.capture(), publication.capture());
        assertEquals("summary", document.getValue().getContent());
        assertEquals("88", document.getValue().getConversationId());
        assertEquals(token.getValue(), document.getValue().getSnapshotToken());
        assertEquals(7L, document.getValue().getLifecycleGeneration());
        verify(publicationCommitService).commit(
                eq(scope.getValue()), eq("88"), eq(List.of(101L)),
                same(publication.getValue()), isNull(), isNull(), anyLong());
        verify(messageDao, never()).findByConversationIdForMaintenance(anyString());
    }

    @Test
    void outOfScopeMessageIsRejectedBeforeSummaryStageOrElasticsearch() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity contaminated = message(
                201L, "89", "tenant one", "PENDING", "1", "cyf", "client-a");
        stubOwnedConversation("89", List.of(contaminated));

        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "89"));

        verify(summaryGenerator, never()).summarizeConversation(anyList());
        verify(publicationDao, never()).stage(any(), anyString(), anyLong(),
                anyString(), anyString(), anyInt(), anyLong());
        verify(memoryRepository, never()).saveConversationSnapshot(any(), any(), any());
    }

    @Test
    void sameMaximumMessageIdButDifferentFullSnapshotProducesDifferentToken() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity before = message(
                301L, "90", "before", "PENDING", "0", "cyf", "client-a");
        ChatMessageEntity after = message(
                301L, "90", "after", "PENDING", "0", "cyf", "client-a");
        when(conversationService.getOwned("owner-a", "client-a", "90"))
                .thenReturn(conversation("90"));
        when(messageDao.findOwnedByConversationId(any(), eq("90")))
                .thenReturn(List.of(before), List.of(after));
        when(summaryGenerator.summarizeConversation(anyList())).thenReturn("summary");

        service.syncOwnedConversation("owner-a", "client-a", "90");
        service.syncOwnedConversation("owner-a", "client-a", "90");

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(publicationDao, times(2)).stage(any(), eq("90"), eq(7L), tokens.capture(),
                anyString(), eq(1), anyLong());
        assertEquals(2, tokens.getAllValues().size());
        assertNotEquals(tokens.getAllValues().get(0), tokens.getAllValues().get(1));
    }

    @Test
    void sqlCommitFailureRetriesSameSqlPayloadWithoutRegeneratingModels() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity source = message(
                401L, "91", "stable", "PENDING", "0", "cyf", "client-a");
        stubOwnedConversation("91", List.of(source));
        when(summaryGenerator.summarizeConversation(List.of(source))).thenReturn("summary");

        AtomicReference<MemoryPublicationRecord> winner = new AtomicReference<>();
        when(publicationDao.stage(any(), eq("91"), eq(7L), anyString(),
                anyString(), eq(1), anyLong())).thenAnswer(invocation -> {
                    MemoryPublicationRecord existing = winner.get();
                    if (existing != null) {
                        return existing;
                    }
                    return allocated(invocation.getArgument(0), "91", 7L,
                            invocation.getArgument(3), invocation.getArgument(4), 1,
                            1_700_000_000_123L).setId(9L).setEsRevision(9L);
                });
        when(publicationDao.bindPayload(any(MemoryPublicationRecord.class),
                any(MemoryPublicationPayload.class), anyLong())).thenAnswer(invocation -> {
                    MemoryPublicationRecord bound = staged(
                            invocation.getArgument(0), invocation.getArgument(1));
                    winner.compareAndSet(null, bound);
                    return winner.get();
                });
        doThrow(new IllegalStateException("SQL commit failed")).doNothing()
                .when(publicationCommitService).commit(
                        any(), anyString(), anyList(), any(), any(), any(), anyLong());

        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "91"));
        service.syncOwnedConversation("owner-a", "client-a", "91");

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(publicationDao, times(2)).stage(any(), eq("91"), eq(7L), tokens.capture(),
                anyString(), eq(1), anyLong());
        assertEquals(tokens.getAllValues().get(0), tokens.getAllValues().get(1));
        verify(summaryGenerator).summarizeConversation(List.of(source));
        verify(memoryRepository).generateEmbedding("summary");
        verify(publicationDao).bindPayload(any(MemoryPublicationRecord.class),
                any(MemoryPublicationPayload.class), anyLong());
        ArgumentCaptor<MemoryPublicationRecord> records =
                ArgumentCaptor.forClass(MemoryPublicationRecord.class);
        verify(memoryRepository, times(2)).saveConversationSnapshot(any(), any(), records.capture());
        assertSame(winner.get(), records.getAllValues().get(0));
        assertSame(winner.get(), records.getAllValues().get(1));
        assertEquals(9L, winner.get().getEsRevision());
    }

    @Test
    void sqlCommitRetryUsesSqlWinnerForByteStableEsConflictRecovery()
            throws Exception {
        ElasticsearchClient elasticsearchClient = mock(ElasticsearchClient.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        MemoryRepositoryImpl realRepository = new MemoryRepositoryImpl();
        set(realRepository, "elasticsearchClient", elasticsearchClient);
        set(realRepository, "embeddingModel", embeddingModel);
        set(realRepository, "embeddingDimension", 2);
        set(realRepository, "memoryPublicationDao", publicationDao);
        set(realRepository, "indexName", "chat_memory");
        set(realRepository, "currentConversationWeight", 1.5d);
        set(realRepository, "sameDayWeight", 1.2d);

        LongTermMemoryServiceImpl service = service(realRepository);
        ChatMessageEntity source = message(
                411L, "911", "stable payload", "PENDING", "0", "cyf", "client-a");
        stubOwnedConversation("911", List.of(source));
        when(summaryGenerator.summarizeConversation(List.of(source)))
                .thenReturn("sql winner summary", "retry drift summary");
        when(embeddingModel.embed("sql winner summary"))
                .thenReturn(new float[]{0.25f, 0.75f});

        long stableTimestamp = 1_700_000_000_456L;
        AtomicReference<MemoryPublicationRecord> winner = new AtomicReference<>();
        when(publicationDao.stage(any(), eq("911"), eq(7L), anyString(),
                anyString(), eq(1), anyLong())).thenAnswer(invocation -> {
                    MemoryPublicationRecord existing = winner.get();
                    if (existing != null) {
                        return existing;
                    }
                    return allocated(invocation.getArgument(0), "911", 7L,
                            invocation.getArgument(3), invocation.getArgument(4), 1,
                            stableTimestamp).setId(19L).setEsRevision(19L);
                });
        when(publicationDao.bindPayload(any(MemoryPublicationRecord.class),
                any(MemoryPublicationPayload.class), anyLong())).thenAnswer(invocation -> {
                    MemoryPublicationRecord bound = staged(
                            invocation.getArgument(0), invocation.getArgument(1));
                    winner.compareAndSet(null, bound);
                    return winner.get();
                });

        AtomicReference<Map<String, Object>> firstPayload = new AtomicReference<>();
        when(elasticsearchClient.index(any(IndexRequest.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    IndexRequest<Map<String, Object>> request = invocation.getArgument(0);
                    firstPayload.set(new HashMap<>(request.document()));
                    return null;
                })
                .thenThrow(conflict());
        when(elasticsearchClient.get(any(Function.class), eq(Map.class)))
                .thenAnswer(invocation -> GetResponse.of(builder -> builder
                        .index("chat_memory")
                        .id(winner.get().getDocumentId())
                        .found(true)
                        .version(winner.get().getEsRevision())
                        .source(firstPayload.get())));
        doThrow(new IllegalStateException("SQL commit failed")).doNothing()
                .when(publicationCommitService).commit(
                        any(), anyString(), anyList(), any(), any(), any(), anyLong());

        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "911"));
        service.syncOwnedConversation("owner-a", "client-a", "911");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<IndexRequest> requests = ArgumentCaptor.forClass(IndexRequest.class);
        verify(elasticsearchClient, times(2)).index(requests.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> first =
                (Map<String, Object>) requests.getAllValues().get(0).document();
        @SuppressWarnings("unchecked")
        Map<String, Object> retry =
                (Map<String, Object>) requests.getAllValues().get(1).document();
        assertEquals(first, retry);
        assertEquals("sql winner summary", first.get("content"));
        assertEquals(List.of(0.25d, 0.75d), first.get("embedding"));
        assertEquals(stableTimestamp, ((Number) first.get("timestamp")).longValue());
        assertEquals(first.get("payload_fingerprint"), retry.get("payload_fingerprint"));
        verify(summaryGenerator).summarizeConversation(List.of(source));
        verify(embeddingModel).embed("sql winner summary");
        verify(embeddingModel, never()).embed("retry drift summary");
        verify(publicationDao).bindPayload(any(MemoryPublicationRecord.class),
                any(MemoryPublicationPayload.class), anyLong());
        verify(elasticsearchClient).get(any(Function.class), eq(Map.class));
        verify(publicationDao, times(2)).stage(any(), eq("911"), eq(7L),
                anyString(), anyString(), eq(1), anyLong());
        verify(publicationCommitService, times(2)).commit(
                any(), eq("911"), eq(List.of(411L)), same(winner.get()),
                isNull(), isNull(), anyLong());
    }

    @Test
    void payloadCasLoserBuildsDocumentFromSqlWinner() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity source = message(
                413L, "913", "concurrent", "PENDING", "0", "cyf", "client-a");
        stubOwnedConversation("913", List.of(source));
        when(summaryGenerator.summarizeConversation(List.of(source)))
                .thenReturn("losing summary");
        when(memoryRepository.generateEmbedding("losing summary"))
                .thenReturn(new float[]{0.1f, 0.9f});
        MemoryPublicationPayload sqlWinner = MemoryPublicationPayload.generated(
                "winner summary", new float[]{0.8f, 0.2f});
        when(publicationDao.bindPayload(any(MemoryPublicationRecord.class),
                any(MemoryPublicationPayload.class), anyLong())).thenAnswer(invocation ->
                staged(invocation.getArgument(0), sqlWinner));

        service.syncOwnedConversation("owner-a", "client-a", "913");

        ArgumentCaptor<MemoryDocument> document = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository).saveConversationSnapshot(any(), document.capture(), any());
        assertEquals("winner summary", document.getValue().getContent());
        assertArrayEquals(new float[]{0.8f, 0.2f}, document.getValue().getEmbedding());
    }

    @Test
    void crashBeforePayloadBindCanRegenerateAndBindOnRetry() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity source = message(
                414L, "914", "retry", "PENDING", "0", "cyf", "client-a");
        stubOwnedConversation("914", List.of(source));
        when(summaryGenerator.summarizeConversation(List.of(source)))
                .thenReturn("first candidate", "second candidate");
        when(memoryRepository.generateEmbedding("first candidate"))
                .thenReturn(new float[]{0.1f, 0.9f});
        when(memoryRepository.generateEmbedding("second candidate"))
                .thenReturn(new float[]{0.2f, 0.8f});
        when(publicationDao.bindPayload(any(MemoryPublicationRecord.class),
                any(MemoryPublicationPayload.class), anyLong()))
                .thenThrow(new IllegalStateException("crash before bind"))
                .thenAnswer(invocation -> staged(
                        invocation.getArgument(0), invocation.getArgument(1)));

        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "914"));
        service.syncOwnedConversation("owner-a", "client-a", "914");

        ArgumentCaptor<MemoryDocument> document = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(memoryRepository).saveConversationSnapshot(any(), document.capture(), any());
        assertEquals("second candidate", document.getValue().getContent());
        assertArrayEquals(new float[]{0.2f, 0.8f}, document.getValue().getEmbedding());
        verify(publicationDao, times(2)).bindPayload(any(MemoryPublicationRecord.class),
                any(MemoryPublicationPayload.class), anyLong());
    }

    @Test
    void stagedPublicationWithCorruptPayloadFailsClosedBeforeModelsOrEs() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity source = message(
                415L, "915", "legacy corrupt", "PENDING", "0", "cyf", "client-a");
        stubOwnedConversation("915", List.of(source));
        when(publicationDao.stage(any(), eq("915"), eq(7L), anyString(),
                anyString(), eq(1), anyLong())).thenAnswer(invocation ->
                allocated(invocation.getArgument(0), "915", 7L,
                        invocation.getArgument(3), invocation.getArgument(4), 1,
                        1_700_000_000_789L)
                        .setState("STAGED")
                        .setSummaryContent("stored")
                        .setEmbeddingBytes(MemoryPublicationPayload.generated(
                                "stored", new float[]{0.3f, 0.7f}).embeddingBytes())
                        .setPayloadDigest("0".repeat(64)));

        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "915"));

        verify(summaryGenerator, never()).summarizeConversation(anyList());
        verify(memoryRepository, never()).generateEmbedding(anyString());
        verify(memoryRepository, never()).saveConversationSnapshot(any(), any(), any());
        verify(publicationDao, never()).bindPayload(any(), any(), anyLong());
        verify(publicationCommitService, never()).commit(
                any(), anyString(), anyList(), any(), any(), any(), anyLong());
    }

    @Test
    void historicalPublicationWithoutPositiveCreateTimeFailsClosedBeforeEs()
            throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity source = message(
                412L, "912", "legacy staged row", "PENDING", "0", "cyf", "client-a");
        stubOwnedConversation("912", List.of(source));
        MemoryPublicationRecord missing = new MemoryPublicationRecord()
                .setId(20L).setSnapshotToken("token").setLifecycleGeneration(7L)
                .setEsRevision(20L).setDocumentId("document").setMessageCount(1)
                .setState("STAGED");
        MemoryPublicationRecord nonPositive = new MemoryPublicationRecord()
                .setId(21L).setSnapshotToken("token").setLifecycleGeneration(7L)
                .setEsRevision(21L).setDocumentId("document").setMessageCount(1)
                .setState("STAGED").setCreateTime(0L);
        when(publicationDao.stage(any(), eq("912"), eq(7L), anyString(),
                anyString(), eq(1), anyLong())).thenReturn(missing, nonPositive);

        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "912"));
        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "912"));

        verify(summaryGenerator, never()).summarizeConversation(anyList());
        verify(memoryRepository, never()).generateEmbedding(anyString());
        verify(memoryRepository, never()).saveConversationSnapshot(any(), any(), any());
        verify(publicationCommitService, never()).commit(
                any(), anyString(), anyList(), any(), any(), any(), anyLong());
    }

    @Test
    void snapshotWithoutPendingRowsStopsBeforeStage() throws Exception {
        LongTermMemoryServiceImpl service = service();
        ChatMessageEntity synced = message(
                501L, "92", "done", "SYNCED", "0", "cyf", "client-a");
        stubOwnedConversation("92", List.of(synced));

        assertThrows(RuntimeException.class,
                () -> service.syncOwnedConversation("owner-a", "client-a", "92"));

        verify(summaryGenerator, never()).summarizeConversation(anyList());
        verify(publicationDao, never()).stage(any(), anyString(), anyLong(),
                anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void protectedTaskThreadWorkIsDeadLetteredWithoutReadingMessages() throws Exception {
        LongTermMemoryServiceImpl service = service();
        MemoryWorkItem work = work(10L, "93", 1);
        when(publicationDao.claimNext(anyString(), anyLong(), anyLong()))
                .thenReturn(work)
                .thenReturn((MemoryWorkItem) null);
        when(memoryGuard.isProtectedConversation("93")).thenReturn(true);

        service.syncPendingConversations();

        verify(publicationDao).dead(eq(work), anyString(),
                eq("task-thread memory is excluded"), anyLong());
        verify(messageDao, never()).findOwnedByConversationId(any(), eq("93"));
    }

    @Test
    void failedWorkRetriesWithoutStarvingNextLegalWork() throws Exception {
        LongTermMemoryServiceImpl service = service();
        MemoryWorkItem poison = work(11L, "94", 1);
        MemoryWorkItem good = work(12L, "95", 1);
        when(publicationDao.claimNext(anyString(), anyLong(), anyLong()))
                .thenReturn(poison)
                .thenReturn(good)
                .thenReturn((MemoryWorkItem) null);
        ChatMessageEntity contaminated = message(
                601L, "94", "bad", "PENDING", "1", "cyf", "client-a");
        ChatMessageEntity legal = message(
                602L, "95", "good", "PENDING", "0", "cyf", "client-a");
        when(messageDao.findOwnedByConversationId(any(), eq("94")))
                .thenReturn(List.of(contaminated));
        when(messageDao.findOwnedByConversationId(any(), eq("95")))
                .thenReturn(List.of(legal));
        when(summaryGenerator.summarizeConversation(List.of(legal))).thenReturn("summary");

        service.syncPendingConversations();

        verify(publicationDao).retry(eq(poison), anyString(), anyLong(),
                contains("out-of-scope"), anyLong());
        verify(publicationCommitService).commit(any(), eq("95"), eq(List.of(602L)),
                any(), eq(good), anyString(), anyLong());
    }

    @Test
    void exhaustedWorkMovesToDeadInsteadOfBlockingQueue() throws Exception {
        LongTermMemoryServiceImpl service = service();
        MemoryWorkItem poison = work(13L, "96", 4);
        when(publicationDao.claimNext(anyString(), anyLong(), anyLong()))
                .thenReturn(poison)
                .thenReturn((MemoryWorkItem) null);
        when(messageDao.findOwnedByConversationId(any(), eq("96")))
                .thenThrow(new IllegalStateException("corrupt snapshot"));

        service.syncPendingConversations();

        verify(publicationDao).dead(eq(poison), anyString(),
                contains("corrupt snapshot"), anyLong());
        verify(publicationDao, never()).retry(eq(poison), anyString(),
                anyLong(), anyString(), anyLong());
    }

    @Test
    void aggregateAndRetentionMaintenanceRemainFailClosed() throws Exception {
        LongTermMemoryServiceImpl service = service();

        assertThrows(IllegalStateException.class, service::generateDailySummary);
        assertThrows(IllegalStateException.class, service::generateWeeklySummary);
        assertThrows(IllegalStateException.class, service::generateMonthlySummary);
        assertThrows(IllegalStateException.class, service::cleanupExpiredMemories);
        assertThrows(IllegalStateException.class,
                () -> service.getConversationMessagesForMaintenance("97"));

        verify(memoryRepository, never()).deleteExpiredDocuments(anyLong());
        verify(messageDao, never()).findActiveJiacns(anyLong());
    }

    private LongTermMemoryServiceImpl service() throws Exception {
        return service(memoryRepository);
    }

    private LongTermMemoryServiceImpl service(MemoryRepository repository) throws Exception {
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl();
        set(service, "chatMessageDao", messageDao);
        set(service, "chatConversationService", conversationService);
        set(service, "summaryGenerator", summaryGenerator);
        set(service, "memoryRepository", repository);
        set(service, "taskThreadMemoryGuard", memoryGuard);
        set(service, "memoryPublicationDao", publicationDao);
        set(service, "publicationCommitService", publicationCommitService);
        set(service, "batchSize", 10);
        set(service, "pendingMaxAttempts", 4);
        set(service, "workLeaseMillis", 60_000L);
        lenient().when(memoryRepository.generateEmbedding(anyString()))
                .thenReturn(new float[]{0.25f, 0.75f});
        lenient().when(publicationDao.stage(any(), anyString(), anyLong(), anyString(),
                anyString(), anyInt(), anyLong())).thenAnswer(invocation ->
                allocated(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3),
                        invocation.getArgument(4), invocation.getArgument(5),
                        invocation.getArgument(6)));
        lenient().when(publicationDao.bindPayload(
                any(MemoryPublicationRecord.class), any(MemoryPublicationPayload.class), anyLong()))
                .thenAnswer(invocation -> staged(
                        invocation.getArgument(0), invocation.getArgument(1)));
        return service;
    }

    private MemoryPublicationRecord allocated(
            ConversationDataScope scope, String conversationId, long generation,
            String token, String documentId, int messageCount, long createTime) {
        return new MemoryPublicationRecord()
                .setId(1L).setTenantId(scope.tenantId()).setScopeMode(scope.mode().name())
                .setScopeKey(scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT
                        ? scope.callerClientId() : scope.dataDomainId())
                .setOwnerJiacn(scope.ownerJiacn()).setConversationId(conversationId)
                .setSnapshotToken(token).setLifecycleGeneration(generation)
                .setEsRevision(1L).setDocumentId(documentId).setMessageCount(messageCount)
                .setState("ALLOCATED").setCreateTime(createTime).setUpdateTime(createTime);
    }

    private MemoryPublicationRecord staged(
            MemoryPublicationRecord allocated, MemoryPublicationPayload payload) {
        return allocated.setState("STAGED")
                .setSummaryContent(payload.summaryContent())
                .setEmbeddingBytes(payload.embeddingBytes())
                .setPayloadDigest(payload.payloadDigest());
    }

    private void stubOwnedConversation(
            String conversationId, List<ChatMessageEntity> messages) {
        when(conversationService.getOwned("owner-a", "client-a", conversationId))
                .thenReturn(conversation(conversationId));
        when(messageDao.findOwnedByConversationId(any(), eq(conversationId)))
                .thenReturn(messages);
    }

    private ChatConversationEntity conversation(String conversationId) {
        return new ChatConversationEntity()
                .setId(Long.parseLong(conversationId))
                .setLifecycleGeneration(7L)
                .setJiacn("owner-a")
                .setDeletedAt(null);
    }

    private ChatMessageEntity message(
            long id, String conversationId, String content, String syncStatus,
            String tenantId, String dataDomainId, String clientId) {
        ChatMessageEntity message = new ChatMessageEntity()
                .setId(id)
                .setConversationId(conversationId)
                .setMessageType("USER")
                .setContent(content)
                .setMetadata("{}")
                .setJiacn("owner-a")
                .setDataDomainId(dataDomainId)
                .setSyncStatus(syncStatus)
                .setConversationType("normal")
                .setSenderType("USER")
                .setSenderName("owner-a");
        message.setCreateTime(100L);
        message.setUpdateTime(100L);
        message.setClientId(clientId);
        message.setTenantId(tenantId);
        return message;
    }

    private MemoryWorkItem work(long id, String conversationId, int attempts) {
        return new MemoryWorkItem()
                .setId(id)
                .setTenantId("0")
                .setScopeMode("LEGACY_CLIENT")
                .setScopeKey("client-a")
                .setOwnerJiacn("owner-a")
                .setConversationId(conversationId)
                .setLifecycleGeneration(7L)
                .setState("LEASED")
                .setAttempts(attempts);
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ElasticsearchException conflict() {
        return new ElasticsearchException("index",
                ErrorResponse.of(response -> response.status(409)
                        .error(error -> error
                                .type("version_conflict_engine_exception")
                                .reason("conflict"))));
    }
}
