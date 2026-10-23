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
                        .source(Map.of("jiacn", "alice", "content", "own"))),
                Hit.<Map>of(h -> h.index("chat_memory_v2").id("other-id").score(1.0)
                        .source(Map.of("jiacn", "bob", "content", "private")))))));
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenReturn(response);
    }

    @Test void bothVectorPathsPreFilterOwnerAndNeverReturnForeignSources() throws Exception {
        results();
        when(embedding.embed("query")).thenReturn(new float[]{1, 0, 0});
        var plain = repository.search("alice", new float[]{1, 0, 0}, 8);
        var boosted = repository.searchWithConversationBoost("alice", "query", null, 8, 0);
        assertEquals(List.of("own-id"), plain.stream().map(MemoryDocument::getId).toList());
        assertEquals(List.of("own-id"), boosted.stream().map(MemoryDocument::getId).toList());
        var capture = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client, times(2)).search(capture.capture(), eq(Map.class));
        for (SearchRequest request : capture.getAllValues()) {
            assertNull(request.query(), "No top-level OR branch bypassing scope");
            var term = request.knn().getFirst().filter().getFirst().term();
            assertEquals("jiacn", term.field());
            assertEquals("alice", term.value().stringValue());
        }
    }

    @Test void providerFailureDoesNotWriteRandomVector() {
        when(embedding.embed("content")).thenThrow(new IllegalStateException("provider404"));
        MemoryDocument doc = new MemoryDocument();doc.setJiacn("alice");doc.setContent("content");
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
        assertThrows(IllegalArgumentException.class, () -> repository.search(" ", new float[]{1,0,0}, 8));
        assertThrows(IllegalArgumentException.class, () -> repository.searchWithConversationBoost("", "query", null, 8, 0));
        verifyNoInteractions(client, embedding);
    }

    @Test void successfulWriteKeepsStableDocumentId() throws Exception {
        when(embedding.embed("content")).thenReturn(new float[]{1,0,0});
        MemoryDocument doc = new MemoryDocument();doc.setJiacn("alice");doc.setContent("content");
        repository.save(doc);
        assertNotNull(doc.getId());
        var capture = ArgumentCaptor.forClass(IndexRequest.class);
        verify(client).index(capture.capture());
        assertEquals(doc.getId(), capture.getValue().id());
        assertEquals(doc.getId(), ((Map<?,?>)capture.getValue().document()).get("id"));
    }
}
