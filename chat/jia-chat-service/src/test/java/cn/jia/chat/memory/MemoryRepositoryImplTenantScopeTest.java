package cn.jia.chat.memory;

import cn.jia.chat.dao.MemoryPublicationDao;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationPayload;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRepositoryImplTenantScopeTest {
    private ElasticsearchClient elasticsearchClient;
    private EmbeddingModel embeddingModel;
    private MemoryPublicationDao publicationDao;
    private MemoryRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        elasticsearchClient = mock(ElasticsearchClient.class);
        embeddingModel = mock(EmbeddingModel.class);
        publicationDao = mock(MemoryPublicationDao.class);
        repository = new MemoryRepositoryImpl();
        ReflectionTestUtils.setField(repository, "elasticsearchClient", elasticsearchClient);
        ReflectionTestUtils.setField(repository, "embeddingModel", embeddingModel);
        ReflectionTestUtils.setField(repository, "memoryPublicationDao", publicationDao);
        ReflectionTestUtils.setField(repository, "indexName", "chat_memory");
        ReflectionTestUtils.setField(repository, "currentConversationWeight", 1.5d);
        ReflectionTestUtils.setField(repository, "sameDayWeight", 1.2d);
        when(publicationDao.findVisibleDocumentIds(any(), anyList())).thenReturn(Set.of());
    }

    @Test
    void directScopedLibraryWritesPublishedDocumentWithExactScope() throws Exception {
        when(embeddingModel.embed("content")).thenReturn(new float[]{0.25f, 0.75f});
        MemoryDocument document = conversationDocument("41", "token-a", 7L);

        repository.save(domainScope("web-client"), document);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<IndexRequest> request = ArgumentCaptor.forClass(IndexRequest.class);
        verify(elasticsearchClient).index(request.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> indexed = (Map<String, Object>) request.getValue().document();
        assertEquals("tester", indexed.get("jiacn"));
        assertEquals("1", indexed.get("tenant_id"));
        assertEquals("cyf", indexed.get("data_domain_id"));
        assertEquals("web-client", indexed.get("client_id"));
        assertEquals("DOMAIN", indexed.get("scope_mode"));
        assertEquals(MemoryDocument.DOCUMENT_KIND_LIBRARY, indexed.get("document_kind"));
        assertEquals(MemoryDocument.PUBLICATION_CLASS_DIRECT, indexed.get("publication_class"));
        assertEquals(MemoryDocument.STATE_PUBLISHED, indexed.get("state"));
        assertEquals(MemoryRepositoryImpl.canonicalPayloadFingerprint(indexed),
                indexed.get("payload_fingerprint"));
    }

    @Test
    void publishedConversationSnapshotUsesExternalVersionAndWritesExactScope() throws Exception {
        when(embeddingModel.embed("content")).thenReturn(new float[]{0.25f, 0.75f});
        ConversationDataScope scope = domainScope("web-client");
        MemoryDocument document = conversationDocument("41", "token-a", 7L);
        MemoryPublicationRecord publication = publication(scope, document, 9L);

        repository.saveConversationSnapshot(scope, document, publication);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<IndexRequest> request = ArgumentCaptor.forClass(IndexRequest.class);
        verify(elasticsearchClient).index(request.capture());
        assertEquals(publication.getDocumentId(), request.getValue().id());
        assertEquals(9L, request.getValue().version());
        assertEquals(VersionType.External, request.getValue().versionType());
        @SuppressWarnings("unchecked")
        Map<String, Object> indexed = (Map<String, Object>) request.getValue().document();
        assertEquals("tester", indexed.get("jiacn"));
        assertEquals("1", indexed.get("tenant_id"));
        assertEquals("cyf", indexed.get("data_domain_id"));
        assertEquals("web-client", indexed.get("client_id"));
        assertEquals("DOMAIN", indexed.get("scope_mode"));
        assertEquals("token-a", indexed.get("snapshot_token"));
        assertEquals(7L, indexed.get("lifecycle_generation"));
        assertEquals(9L, indexed.get("snapshot_version"));
        assertEquals(MemoryDocument.DOCUMENT_KIND_CONVERSATION_SUMMARY, indexed.get("document_kind"));
        assertEquals(MemoryDocument.PUBLICATION_CLASS_MANIFEST, indexed.get("publication_class"));
        assertEquals(MemoryDocument.STATE_PUBLISHED, indexed.get("state"));
        assertEquals(MemoryRepositoryImpl.canonicalPayloadFingerprint(indexed),
                indexed.get("payload_fingerprint"));
        verify(embeddingModel, never()).embed("content");
    }

    @Test
    void identicalPublicationRetryReusesIdAndStrictExternalVersion() throws Exception {
        when(embeddingModel.embed("content")).thenReturn(new float[]{0.25f, 0.75f});
        ConversationDataScope scope = legacyScope("web-client");
        MemoryDocument first = conversationDocument("41", "token-a", 7L);
        MemoryDocument second = conversationDocument("41", "token-a", 7L);
        MemoryPublicationRecord publication = publication(scope, first, 11L);
        second.setEmbedding(first.getEmbedding());

        repository.saveConversationSnapshot(scope, first, publication);
        repository.saveConversationSnapshot(scope, second, publication);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<IndexRequest> request = ArgumentCaptor.forClass(IndexRequest.class);
        verify(elasticsearchClient, times(2)).index(request.capture());
        List<IndexRequest> requests = request.getAllValues();
        assertEquals(requests.get(0).id(), requests.get(1).id());
        assertEquals(11L, requests.get(0).version());
        assertEquals(11L, requests.get(1).version());
        assertEquals(VersionType.External, requests.get(0).versionType());
        assertEquals(VersionType.External, requests.get(1).versionType());
        verify(embeddingModel, never()).embed("content");
    }


    @Test
    void versionConflictSucceedsOnlyAfterExactExistingSnapshotVerification() throws Exception {
        when(embeddingModel.embed("content")).thenReturn(new float[]{0.25f, 0.75f});
        ConversationDataScope scope = domainScope("web-client");
        MemoryDocument document = conversationDocument("41", "token-a", 7L);
        MemoryPublicationRecord publication = publication(scope, document, 9L);
        when(elasticsearchClient.index(any(IndexRequest.class))).thenThrow(conflict());
        Map<String, Object> source = snapshotSource(scope, publication);
        GetResponse<Map> existing = getResponse(
                publication.getDocumentId(), publication.getEsRevision(), source);
        when(elasticsearchClient.get(
                any(Function.class), eq(Map.class))).thenReturn(existing);

        repository.saveConversationSnapshot(scope, document, publication);

        verify(elasticsearchClient).get(any(Function.class), eq(Map.class));
    }

    @Test
    void versionConflictRejectsTokenRevisionAndScopeMismatch() throws Exception {
        when(embeddingModel.embed("content")).thenReturn(new float[]{0.25f, 0.75f});
        ConversationDataScope scope = domainScope("web-client");
        MemoryDocument document = conversationDocument("41", "token-a", 7L);
        MemoryPublicationRecord publication = publication(scope, document, 9L);
        when(elasticsearchClient.index(any(IndexRequest.class))).thenThrow(conflict());
        Map<String, Object> wrongToken = snapshotSource(scope, publication);
        wrongToken.put("snapshot_token", "token-b");
        Map<String, Object> wrongScope = snapshotSource(scope, publication);
        wrongScope.put("data_domain_id", "foreign");
        GetResponse<Map> wrongRevision = getResponse(
                publication.getDocumentId(), 10L, snapshotSource(scope, publication));
        when(elasticsearchClient.get(
                any(Function.class), eq(Map.class))).thenReturn(
                getResponse(publication.getDocumentId(), 9L, wrongToken),
                wrongRevision,
                getResponse(publication.getDocumentId(), 9L, wrongScope));

        assertThrows(IllegalStateException.class,
                () -> repository.saveConversationSnapshot(scope, document, publication));
        assertThrows(IllegalStateException.class,
                () -> repository.saveConversationSnapshot(scope, document, publication));
        assertThrows(IllegalStateException.class,
                () -> repository.saveConversationSnapshot(scope, document, publication));
    }
    @Test
    void versionConflictRejectsContentSummaryTypeAndEmbeddingMismatch() throws Exception {
        when(embeddingModel.embed("content")).thenReturn(new float[]{0.25f, 0.75f});
        ConversationDataScope scope = domainScope("web-client");
        MemoryDocument document = conversationDocument("41", "token-a", 7L);
        MemoryPublicationRecord publication = publication(scope, document, 9L);
        when(elasticsearchClient.index(any(IndexRequest.class))).thenThrow(conflict());

        Map<String, Object> wrongContent = snapshotSource(scope, publication);
        wrongContent.put("content", "different content");
        Map<String, Object> wrongSummaryType = snapshotSource(scope, publication);
        wrongSummaryType.put("summary_type", "daily_summary");
        Map<String, Object> wrongEmbedding = snapshotSource(scope, publication);
        wrongEmbedding.put("embedding", List.of(0.25d, 0.5d));
        when(elasticsearchClient.get(
                any(Function.class), eq(Map.class))).thenReturn(
                getResponse(publication.getDocumentId(), 9L, wrongContent),
                getResponse(publication.getDocumentId(), 9L, wrongSummaryType),
                getResponse(publication.getDocumentId(), 9L, wrongEmbedding));

        assertThrows(IllegalStateException.class,
                () -> repository.saveConversationSnapshot(scope, document, publication));
        assertThrows(IllegalStateException.class,
                () -> repository.saveConversationSnapshot(scope, document, publication));
        assertThrows(IllegalStateException.class,
                () -> repository.saveConversationSnapshot(scope, document, publication));
    }

    @Test
    void documentIdentitySeparatesLegacyClientsButDomainSharesAcrossGrantedClients() {
        String legacyWeb = MemoryRepositoryImpl.deterministicConversationDocumentId(
                legacyScope("web-client"), "41", "token-a");
        String legacyMobile = MemoryRepositoryImpl.deterministicConversationDocumentId(
                legacyScope("mobile-client"), "41", "token-a");
        String domainWeb = MemoryRepositoryImpl.deterministicConversationDocumentId(
                domainScope("web-client"), "41", "token-a");
        String domainMobile = MemoryRepositoryImpl.deterministicConversationDocumentId(
                domainScope("mobile-client"), "41", "token-a");

        assertNotEquals(legacyWeb, legacyMobile);
        assertEquals(domainWeb, domainMobile);
        assertNotEquals(domainWeb, legacyWeb);
    }

    @Test
    void domainSearchUsesExactScopeAndSqlPublicationVisibility() throws Exception {
        when(embeddingModel.embed("question")).thenReturn(new float[]{0.5f, 0.5f});
        ConversationDataScope scope = domainScope("mobile-client");
        SearchResponse<Map> response = response(hit("visible",
                manifestSource("1", "cyf", "web-client", "DOMAIN", "visible content")));
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);
        when(publicationDao.findVisibleDocumentIds(scope, List.of("visible")))
                .thenReturn(Set.of("visible"));

        List<MemoryDocument> results = repository.searchWithConversationBoost(
                scope, "question", "41", 8, 0.3d);

        assertEquals(List.of("visible content"), results.stream()
                .map(MemoryDocument::getContent).toList());
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(elasticsearchClient).search(request.capture(), eq(Map.class));
        assertEquals(Map.of(
                "jiacn.keyword", "tester",
                "tenant_id.keyword", "1",
                "scope_mode.keyword", "DOMAIN",
                "state.keyword", "PUBLISHED",
                "data_domain_id.keyword", "cyf"), knnTerms(request.getValue()));
        verify(publicationDao).findVisibleDocumentIds(scope, List.of("visible"));
    }


    @Test
    void domainDirectDocumentsAreSharedAcrossGrantedClientsWithoutSqlLookup() throws Exception {
        when(embeddingModel.embed("question")).thenReturn(new float[]{0.5f, 0.5f});
        ConversationDataScope scope = domainScope("mobile-client");
        SearchResponse<Map> response = response(hit("direct", directSource(
                "1", "cyf", "web-client", "DOMAIN", "shared library")));
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);

        List<MemoryDocument> results = repository.searchWithConversationBoost(
                scope, "question", "41", 8, 0.3d);

        assertEquals(List.of("shared library"), results.stream()
                .map(MemoryDocument::getContent).toList());
        verify(publicationDao, never()).findVisibleDocumentIds(any(), anyList());
    }

    @Test
    void mixedSearchChecksOnlyManifestIdsAgainstSqlAuthority() throws Exception {
        when(embeddingModel.embed("question")).thenReturn(new float[]{0.5f, 0.5f});
        ConversationDataScope scope = domainScope("mobile-client");
        SearchResponse<Map> response = response(
                hit("direct", directSource(
                        "1", "cyf", "web-client", "DOMAIN", "shared library")),
                hit("manifest", manifestSource(
                        "1", "cyf", "web-client", "DOMAIN", "published summary")));
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);
        when(publicationDao.findVisibleDocumentIds(scope, List.of("manifest")))
                .thenReturn(Set.of("manifest"));

        List<MemoryDocument> results = repository.searchWithConversationBoost(
                scope, "question", "41", 8, 0.3d);

        assertEquals(Set.of("shared library", "published summary"), results.stream()
                .map(MemoryDocument::getContent).collect(Collectors.toSet()));
        verify(publicationDao).findVisibleDocumentIds(scope, List.of("manifest"));
    }
    @Test
    void legacySearchFiltersClientAndRejectsSameOwnerDifferentClient() throws Exception {
        when(embeddingModel.embed("question")).thenReturn(new float[]{0.5f, 0.5f});
        ConversationDataScope scope = legacyScope("web-client");
        SearchResponse<Map> response = response(hit("mobile",
                directSource("0", "cyf", "mobile-client", "LEGACY_CLIENT", "foreign client")));
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);

        assertEquals(List.of(), repository.searchWithConversationBoost(
                scope, "question", "41", 8, 0.3d));

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(elasticsearchClient).search(request.capture(), eq(Map.class));
        assertEquals(Map.of(
                "jiacn.keyword", "tester",
                "tenant_id.keyword", "0",
                "scope_mode.keyword", "LEGACY_CLIENT",
                "state.keyword", "PUBLISHED",
                "client_id.keyword", "web-client"), knnTerms(request.getValue()));
        verify(publicationDao, never()).findVisibleDocumentIds(any(), anyList());
    }

    @Test
    void fieldlessHistoryAndSqlOrphansAreInvisible() throws Exception {
        when(embeddingModel.embed("question")).thenReturn(new float[]{0.5f, 0.5f});
        ConversationDataScope scope = legacyScope("web-client");
        SearchResponse<Map> response = response(
                hit("fieldless", Map.of("jiacn", "tester", "content", "legacy")),
                hit("orphan",
                        manifestSource("0", "cyf", "web-client", "LEGACY_CLIENT", "orphan")));
        when(elasticsearchClient.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);
        when(publicationDao.findVisibleDocumentIds(scope, List.of("orphan")))
                .thenReturn(Set.of());

        assertEquals(List.of(), repository.searchWithConversationBoost(
                scope, "question", "41", 8, 0.3d));

        verify(publicationDao).findVisibleDocumentIds(scope, List.of("orphan"));
    }

    @Test
    void ownerOnlyAndUnscopedMaintenanceOperationsFailClosed() {
        assertThrows(IllegalStateException.class,
                () -> repository.save(new MemoryDocument()));
        assertThrows(IllegalStateException.class,
                () -> repository.saveAll(List.of(new MemoryDocument())));
        assertThrows(IllegalStateException.class,
                () -> repository.search("tester", new float[]{0.1f}, 3));
        assertThrows(IllegalStateException.class,
                () -> repository.searchWithConversationBoost("tester", "q", "41", 3, 0.2d));
        assertThrows(IllegalStateException.class,
                () -> repository.findConversationSummariesByJiacn("tester", 1L));
        assertThrows(IllegalStateException.class,
                () -> repository.findDailySummariesByJiacn("tester", 1L));
        assertThrows(IllegalStateException.class,
                () -> repository.findWeeklySummariesByJiacn("tester", 1L));
        assertThrows(IllegalStateException.class,
                () -> repository.deleteExpiredDocuments(1L));
        assertThrows(IllegalStateException.class,
                () -> repository.deleteOldDocuments("conversation", 3));
        assertThrows(IllegalStateException.class,
                () -> repository.countByJiacn("tester"));
    }

    private Map<String, String> knnTerms(SearchRequest request) {
        assertEquals(null, request.query());
        assertEquals(1, request.knn().size());
        List<Query> filters = request.knn().getFirst().filter();
        assertTrue(filters.stream().allMatch(Query::isTerm));
        return filters.stream().collect(Collectors.toMap(
                query -> query.term().field(),
                query -> query.term().value().stringValue()));
    }

    @SafeVarargs
    private final SearchResponse<Map> response(Hit<Map>... hitsToReturn) {
        @SuppressWarnings("unchecked")
        SearchResponse<Map> response = mock(SearchResponse.class);
        @SuppressWarnings("unchecked")
        HitsMetadata<Map> hits = mock(HitsMetadata.class);
        when(response.hits()).thenReturn(hits);
        when(hits.hits()).thenReturn(List.of(hitsToReturn));
        return response;
    }

    private Hit<Map> hit(String id, Map<String, Object> source) {
        return Hit.of(hit -> hit.index("chat_memory").id(id).score(1.0d).source(source));
    }

    private Map<String, Object> directSource(
            String tenantId, String domainId, String clientId,
            String scopeMode, String content) {
        return scopedSource(tenantId, domainId, clientId, scopeMode, content,
                MemoryDocument.DOCUMENT_KIND_LIBRARY,
                MemoryDocument.PUBLICATION_CLASS_DIRECT);
    }

    private Map<String, Object> manifestSource(
            String tenantId, String domainId, String clientId,
            String scopeMode, String content) {
        Map<String, Object> source = scopedSource(
                tenantId, domainId, clientId, scopeMode, content,
                MemoryDocument.DOCUMENT_KIND_CONVERSATION_SUMMARY,
                MemoryDocument.PUBLICATION_CLASS_MANIFEST);
        source.put("conversation_id", "41");
        return source;
    }

    private Map<String, Object> scopedSource(
            String tenantId, String domainId, String clientId, String scopeMode,
            String content, String documentKind, String publicationClass) {
        Map<String, Object> source = new HashMap<>();
        source.put("jiacn", "tester");
        source.put("tenant_id", tenantId);
        source.put("data_domain_id", domainId);
        source.put("client_id", clientId);
        source.put("scope_mode", scopeMode);
        source.put("content", content);
        source.put("document_kind", documentKind);
        source.put("publication_class", publicationClass);
        source.put("state", MemoryDocument.STATE_PUBLISHED);
        return source;
    }


    private Map<String, Object> snapshotSource(
            ConversationDataScope scope, MemoryPublicationRecord publication) {
        Map<String, Object> source = scopedSource(scope.tenantId(), scope.dataDomainId(),
                scope.callerClientId(), scope.mode().name(), "content",
                MemoryDocument.DOCUMENT_KIND_CONVERSATION_SUMMARY,
                MemoryDocument.PUBLICATION_CLASS_MANIFEST);
        source.put("id", publication.getDocumentId());
        source.put("conversation_id", publication.getConversationId());
        source.put("snapshot_token", publication.getSnapshotToken());
        source.put("summary_type", "conversation");
        source.put("timestamp", 123456789L);
        source.put("topic", "topic");
        source.put("key_points", List.of("point-a", "point-b"));
        source.put("categories", List.of("category-a"));
        source.put("preferences", List.of("preference-a"));
        source.put("period_start", 100L);
        source.put("period_end", 200L);
        source.put("conversation_count", 1);
        source.put("embedding", List.of(0.25d, 0.75d));
        source.put("snapshot_version", publication.getEsRevision());
        source.put("lifecycle_generation", publication.getLifecycleGeneration());
        source.put("payload_fingerprint",
                MemoryRepositoryImpl.canonicalPayloadFingerprint(source));
        return source;
    }

    private GetResponse<Map> getResponse(String id, long version, Map<String, Object> source) {
        return GetResponse.of(builder -> builder
                .index("chat_memory")
                .id(id)
                .found(true)
                .version(version)
                .source(source));
    }

    private ElasticsearchException conflict() {
        return new ElasticsearchException("index",
                ErrorResponse.of(response -> response.status(409)
                        .error(error -> error
                                .type("version_conflict_engine_exception")
                                .reason("conflict"))));
    }


    private MemoryPublicationRecord publication(
            ConversationDataScope scope, MemoryDocument document, long revision) {
        MemoryPublicationPayload payload = MemoryPublicationPayload.generated(
                document.getContent(), new float[]{0.25f, 0.75f});
        document.setEmbedding(payload.embedding());
        return new MemoryPublicationRecord()
                .setId(revision)
                .setTenantId(scope.tenantId())
                .setScopeMode(scope.mode().name())
                .setScopeKey(scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT
                        ? scope.callerClientId() : scope.dataDomainId())
                .setOwnerJiacn(scope.ownerJiacn())
                .setConversationId(document.getConversationId())
                .setLifecycleGeneration(document.getLifecycleGeneration())
                .setSnapshotToken(document.getSnapshotToken())
                .setEsRevision(revision)
                .setDocumentId(MemoryRepositoryImpl.deterministicConversationDocumentId(
                        scope, document.getConversationId(), document.getSnapshotToken()))
                .setMessageCount(1)
                .setState("STAGED")
                .setSummaryContent(payload.summaryContent())
                .setEmbeddingBytes(payload.embeddingBytes())
                .setPayloadDigest(payload.payloadDigest())
                .setCreateTime(document.getTimestamp());
    }

    private MemoryDocument conversationDocument(
            String conversationId, String token, long generation) {
        MemoryDocument document = new MemoryDocument();
        document.setConversationId(conversationId);
        document.setSummaryType("conversation");
        document.setContent("content");
        document.setTimestamp(123456789L);
        document.setTopic("topic");
        document.setKeyPoints(List.of("point-a", "point-b"));
        document.setCategories(List.of("category-a"));
        document.setPreferences(List.of("preference-a"));
        document.setPeriodStart(100L);
        document.setPeriodEnd(200L);
        document.setConversationCount(1);
        document.setSnapshotToken(token);
        document.setLifecycleGeneration(generation);
        return document;
    }

    private ConversationDataScope legacyScope(String clientId) {
        return new ConversationDataScope(
                new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"),
                        "tester", clientId),
                ConversationDataScope.Mode.LEGACY_CLIENT);
    }

    private ConversationDataScope domainScope(String clientId) {
        return new ConversationDataScope(
                new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"),
                        "tester", clientId),
                ConversationDataScope.Mode.DOMAIN);
    }
}
