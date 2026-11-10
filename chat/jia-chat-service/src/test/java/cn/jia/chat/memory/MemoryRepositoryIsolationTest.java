package cn.jia.chat.memory;

import cn.jia.test.BaseMockTest;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryRepositoryIsolationTest extends BaseMockTest {
    @Mock ElasticsearchClient client;
    @Mock EmbeddingModel embedding;
    MemoryRepositoryImpl repository;

    @BeforeEach void setup() {
        repository = new MemoryRepositoryImpl();
        ReflectionTestUtils.setField(repository, "processScope", new cn.jia.core.security.FixedTenantAdmissionPolicy("tenant-a", null));
        ReflectionTestUtils.setField(repository, "elasticsearchClient", client);
        ReflectionTestUtils.setField(repository, "embeddingModel", embedding);
        ReflectionTestUtils.setField(repository, "indexName", "chat_memory");
        ReflectionTestUtils.setField(repository, "embeddingDimension", 3);
    }

    @SuppressWarnings("unchecked")
    void results() throws Exception {
        SearchResponse<Map> response = mock(SearchResponse.class);
        when(response.hits()).thenReturn(HitsMetadata.<Map>of(b -> b.hits(List.of(
                Hit.<Map>of(h -> h.index("chat_memory_v2").id("own-id").score(1.0)
                        .source(Map.of("tenant_id", "tenant-a", "client_id", "client-a", "jiacn", "alice", "content", "own")))))));
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);
    }

    @Test void bothVectorPathsPreFilterAllIdentityDimensionsBeforeRanking() throws Exception {
        results();
        when(embedding.embed("query")).thenReturn(new float[]{1, 0, 0});
        var plain = repository.search("client-a", "alice", new float[]{1, 0, 0}, 8);
        var boosted = repository.searchWithConversationBoost("client-a", "alice", "query", null, 8, 0);
        assertEquals(List.of("own-id"), plain.stream().map(MemoryDocument::getId).toList());
        assertEquals(List.of("own-id"), boosted.stream().map(MemoryDocument::getId).toList());
        var capture = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client, times(2)).search(capture.capture(), eq(Map.class));
        for (SearchRequest request : capture.getAllValues()) {
            assertNull(request.query(), "No top-level OR branch bypassing scope");
            assertScope(request.knn().getFirst().filter().getFirst(), "client-a");
        }
    }

    @Test void providerFailureDoesNotWriteRandomVector() {
        when(embedding.embed("content")).thenThrow(new IllegalStateException("provider404"));
        MemoryDocument doc = new MemoryDocument();doc.setJiacn("alice");doc.setClientId("client-a");doc.setContent("content");
        assertThrows(RuntimeException.class, () -> repository.save(doc));
        verifyNoInteractions(client);
    }

    @Test void badDimensionZeroAndNonFiniteVectorsAreRejected() {
        for (float[] vector : new float[][]{ {}, {1}, {0,0,0}, {Float.NaN,1,0}, {Float.POSITIVE_INFINITY,0,0}}) {
            when(embedding.embed("query")).thenReturn(vector);
            assertThrows(RuntimeException.class, () -> repository.generateEmbedding("query"));
        }
        verifyNoInteractions(client);
    }

    @Test void blankOwnerFailsBeforeProviderOrSearch() {
        assertThrows(IllegalArgumentException.class, () -> repository.search("client-a", " ", new float[]{1,0,0}, 8));
        assertThrows(IllegalArgumentException.class, () -> repository.searchWithConversationBoost("client-a", "", "query", null, 8, 0));
        verifyNoInteractions(client, embedding);
    }

    @Test void successfulWriteKeepsStableDocumentId() throws Exception {
        when(embedding.embed("content")).thenReturn(new float[]{1,0,0});
        MemoryDocument doc = new MemoryDocument();doc.setJiacn("alice");doc.setClientId("client-a");doc.setContent("content");
        repository.save(doc);
        assertNotNull(doc.getId());
        var capture = ArgumentCaptor.forClass(IndexRequest.class);
        verify(client).index(capture.capture());
        assertEquals(doc.getId(), capture.getValue().id());
        var document = (Map<?,?>) capture.getValue().document();
        assertEquals(doc.getId(), document.get("id"));
        assertEquals("tenant-a", document.get("tenant_id"));
        assertEquals("client-a", document.get("client_id"));
    }
    static void assertScope(co.elastic.clients.elasticsearch._types.query_dsl.Query query, String client) {
        var terms = query.bool().filter().stream().map(q -> q.term()).collect(java.util.stream.Collectors.toMap(
                t -> t.field(), t -> t.value().stringValue()));
        assertEquals(Map.of("tenant_id", "tenant-a", "client_id", client, "jiacn", "alice"), terms);
        assertTrue(query.bool().should().isEmpty());
    }

    @Test void summariesAndCountShareTheExactScopeBeforeLimit() throws Exception {
        results();
        repository.findConversationSummariesByJiacn("client-a", "alice", 100);
        repository.findDailySummariesByJiacn("client-a", "alice", 100);
        repository.findWeeklySummariesByJiacn("client-a", "alice", 100);
        repository.countByJiacn("client-a", "alice");
        var requests = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client, times(4)).search(requests.capture(), eq(Map.class));
        for (var request : requests.getAllValues()) assertScope(request.query().bool().filter().getFirst(), "client-a");
    }

    @Test void missingOrUnadmittedClientNeverInvokesProviderOrIndex() {
        ReflectionTestUtils.setField(repository, "processScope", new cn.jia.core.security.FixedTenantAdmissionPolicy("tenant-a", "client-a"));
        for (String clientId : new String[]{null, "", "other-client"}) {
            assertThrows(IllegalArgumentException.class, () -> repository.search(clientId, "alice", new float[]{1,0,0}, 8));
            var doc = new MemoryDocument(); doc.setJiacn("alice"); doc.setClientId(clientId); doc.setContent("private");
            assertThrows(IllegalArgumentException.class, () -> repository.save(doc));
        }
        verifyNoInteractions(client, embedding);
    }

    @Test void maintenanceDeletionNeverCrossesProcessPartition() throws Exception {
        ReflectionTestUtils.setField(repository, "processScope", new cn.jia.core.security.FixedTenantAdmissionPolicy("tenant-a", "client-a"));
        repository.deleteExpiredDocuments(100);
        var requests = ArgumentCaptor.forClass(co.elastic.clients.elasticsearch.core.DeleteByQueryRequest.class);
        verify(client).deleteByQuery(requests.capture());
        var filters = requests.getValue().query().bool().filter().getFirst().bool().filter();
        var terms = filters.stream().map(q -> q.term()).collect(java.util.stream.Collectors.toMap(t -> t.field(), t -> t.value().stringValue()));
        assertEquals(Map.of("tenant_id", "tenant-a", "client_id", "client-a"), terms);
    }

    @Test void additiveMappingUsesExactKeywordFieldsWithoutGuessingHistoricalIdentity() throws Exception {
        try (var input = getClass().getResourceAsStream("/db/chat-memory-scope-mapping-v1.json")) {
            assertNotNull(input);
            var mapping = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input).path("properties");
            assertEquals(2, mapping.size());
            assertEquals("keyword", mapping.path("tenant_id").path("type").asText());
            assertEquals("keyword", mapping.path("client_id").path("type").asText());
        }
    }

}
