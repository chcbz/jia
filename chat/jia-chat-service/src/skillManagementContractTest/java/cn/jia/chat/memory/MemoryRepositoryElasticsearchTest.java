package cn.jia.chat.memory;

import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual repository/client/server, synthetic data only on an owned disposable ES node. */
class MemoryRepositoryElasticsearchTest {
    @Test void exactScopePrecedesVectorRankingSummaryLimitCountAndMaintenance() throws Exception {
        String endpoint = System.getenv("CYF_MEMORY_ES_URL");
        assertNotNull(endpoint, "Start the task-owned Elasticsearch fixture; production is never a default");
        URI uri = URI.create(endpoint);
        assertEquals("127.0.0.1", uri.getHost());
        assertNotEquals(9200, uri.getPort(), "Never mutate the production ES listener");
        String cluster = System.getenv("CYF_MEMORY_ES_CLUSTER");
        assertNotNull(cluster);
        String index = "cyf-memory-scope-" + UUID.randomUUID().toString().replace("-", "");
        ElasticsearchClient client = ElasticsearchClient.of(b -> b.host(endpoint));
        boolean created = false;
        try {
            assertEquals(cluster, client.info().clusterName());
            assertTrue(cluster.startsWith("cyf-task-scheduling-regression-"));
            try (var baseline = getClass().getResourceAsStream("/memory-index-baseline-20261111.json")) {
                assertNotNull(baseline);
                assertTrue(client.indices().create(b -> b.index(index).withJson(baseline)).acknowledged());
                created = true;
            }
            try (var addition = getClass().getResourceAsStream("/db/chat-memory-scope-mapping-v1.json")) {
                assertNotNull(addition);
                assertTrue(client.indices().putMapping(b -> b.index(index).withJson(addition)).acknowledged());
            }
            var properties = client.indices().getMapping(b -> b.index(index)).result().get(index).mappings().properties();
            assertTrue(properties.get("tenant_id").isKeyword());
            assertTrue(properties.get("client_id").isKeyword());
            assertEquals(1024, properties.get("embedding").denseVector().dims());

            var embedding = mock(EmbeddingModel.class);
            when(embedding.embed("synthetic query")).thenReturn(vector(true));
            var repository = new MemoryRepositoryImpl();
            ReflectionTestUtils.setField(repository, "elasticsearchClient", client);
            ReflectionTestUtils.setField(repository, "embeddingModel", embedding);
            ReflectionTestUtils.setField(repository, "processScope", new FixedTenantAdmissionPolicy("tenant-a", "client-a"));
            ReflectionTestUtils.setField(repository, "indexName", index);
            ReflectionTestUtils.setField(repository, "embeddingDimension", 1024);
            ReflectionTestUtils.setField(repository, "currentConversationWeight", 1.5);
            ReflectionTestUtils.setField(repository, "sameDayWeight", 1.2);
            ReflectionTestUtils.setField(repository, "taskThreadMemoryGuard", mock(AgentTaskThreadMemoryGuard.class));
            for (String kind : List.of("conversation", "daily_summary", "weekly_summary")) {
                MemoryDocument document = new MemoryDocument();
                document.setId("own-" + kind); document.setClientId("client-a"); document.setJiacn("alice");
                document.setConversationId("synthetic-conversation"); document.setSummaryType(kind);
                document.setContent("own " + kind); document.setTimestamp(1L); document.setEmbedding(vector(false));
                repository.savePrepared(document);
            }
            // More than both numCandidates and summary size: post-query filtering would lose own records.
            List<String> strangers = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                String id = "other-client-" + i; strangers.add(id);
                client.index(b -> b.index(index).id(id).document(foreign("tenant-a", "other-client", "alice")));
            }
            List<Map<String,Object>> boundaries = List.of(foreign("other-tenant", "client-a", "alice"),
                    foreign("Tenant-a", "client-a", "alice"), foreign("tenant-a ", "client-a", "alice"),
                    foreign("tenant-a", "Client-a", "alice"), foreign("tenant-a", "client-a ", "alice"),
                    foreign("tenant-a", "client-a", "Alice"), foreign("tenant-a", "client-a", "alice "));
            for (int i = 0; i < boundaries.size(); i++) {
                String id = "boundary-" + i;
                if (i < 5) strangers.add(id); // Other owners in the admitted partition are eligible for maintenance.
                var document = boundaries.get(i);
                client.index(b -> b.index(index).id(id).document(document));
            }
            var legacy = foreign("tenant-a", "client-a", "alice");
            legacy.remove("tenant_id"); legacy.remove("client_id"); strangers.add("legacy");
            client.index(b -> b.index(index).id("legacy").document(legacy));
            client.indices().refresh(b -> b.index(index));
            assertEquals(3, repository.countByJiacn("client-a", "alice"));
            for (var result : List.of(repository.search("client-a", "alice", vector(true), 1),
                    repository.searchWithConversationBoost("client-a", "alice", "synthetic query", "synthetic-conversation", 1, 0))) {
                assertEquals(1, result.size()); assertTrue(result.getFirst().getId().startsWith("own-"));
            }
            assertEquals(List.of("own conversation"), repository.findConversationSummariesByJiacn("client-a", "alice", 0));
            assertEquals(List.of("own daily_summary"), repository.findDailySummariesByJiacn("client-a", "alice", 0));
            assertEquals(List.of("own weekly_summary"), repository.findWeeklySummariesByJiacn("client-a", "alice", 0));
            repository.deleteExpiredDocuments(10000);
            client.indices().refresh(b -> b.index(index));
            assertEquals(2, repository.countByJiacn("client-a", "alice"));
            assertFalse(client.exists(b -> b.index(index).id("boundary-5")).value());
            assertFalse(client.exists(b -> b.index(index).id("boundary-6")).value());
            assertEquals(strangers.size() + 2, client.count(b -> b.index(index)).count());
            verify(embedding, times(1)).embed("synthetic query"); verifyNoMoreInteractions(embedding);
        } finally {
            try { if (created) assertTrue(client.indices().delete(b -> b.index(index)).acknowledged()); }
            finally { client._transport().close(); }
        }
    }
    static float[] vector(boolean closer) {
        float[] result = new float[1024]; result[0] = closer ? 1 : 0.5f; result[1] = closer ? 0 : 0.8660254f; return result;
    }
    static Map<String,Object> foreign(String tenant, String client, String owner) {
        Map<String,Object> result = new HashMap<>();
        result.put("tenant_id", tenant); result.put("client_id", client); result.put("jiacn", owner);
        result.put("content", "synthetic foreign"); result.put("summary_type", "conversation");
        result.put("timestamp", 9999L); result.put("embedding", vector(true)); return result;
    }
}
