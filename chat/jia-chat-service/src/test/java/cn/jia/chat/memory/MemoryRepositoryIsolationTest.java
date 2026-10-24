package cn.jia.chat.memory;

import cn.jia.test.BaseMockTest;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.DataDomainId;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Embedding failure/integrity checks retained after retiring owner-only memory APIs.
 * Scoped storage/search isolation is covered by MemoryRepositoryImplTenantScopeTest. */
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

    @Test void providerFailureDoesNotWriteRandomVector() {
        when(embedding.embed("content")).thenThrow(new IllegalStateException("provider404"));
        MemoryDocument doc = new MemoryDocument();doc.setJiacn("alice");doc.setContent("content");
        var scope = new ConversationDataScope(new BusinessScope(TenantId.of("0"),
                DataDomainId.of("cyf"), "alice", "web-client"), ConversationDataScope.Mode.LEGACY_CLIENT);
        assertThrows(RuntimeException.class, () -> repository.save(scope, doc));
        verify(embedding).embed("content");
        verifyNoInteractions(client);
    }

    @Test void badDimensionZeroAndNonFiniteVectorsAreRejected() {
        for (float[] vector : new float[][]{ {}, {1}, {0,0,0}, {Float.NaN,1,0}, {Float.POSITIVE_INFINITY,0,0}}) {
            when(embedding.embed("query")).thenReturn(vector);
            assertThrows(RuntimeException.class, () -> repository.generateEmbedding("query"));
        }
        verifyNoInteractions(client);
    }

}
