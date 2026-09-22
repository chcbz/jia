package cn.jia.agent.mapper;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HallReadMapperContractTest {
    @Test void allQueriesBindExactScopeAndDoNotSelectPrivateBodiesOrMutableClientSql() {
        Configuration configuration = new Configuration(); configuration.addMapper(HallReadMapper.class);
        for (String kind : new String[]{"private", "task", "draft"}) {
            Map<String, Object> params = new HashMap<>(Map.of("tenantId", "0", "clientId", "c", "ownerJiacn", "o",
                    "kind", kind, "view", "recent", "q", "quoted%'\\_", "limit", 21));
            params.put("beforeUpdatedAt", 19L); params.put("beforeSourceType", "DRAFT"); params.put("beforeId", "id");
            String sql = configuration.getMappedStatement(HallReadMapper.class.getName() + ".page")
                    .getBoundSql(params).getSql();
            for (String field : new String[]{"tenant_id", "client_id", "owner_jiacn"}) {
                assertTrue(sql.contains(field + " AS BINARY)")); assertTrue(sql.contains("OCTET_LENGTH("));
            }
            assertTrue(sql.contains("LOCATE(LOWER(?)")); assertFalse(sql.contains("quoted%"));
            assertTrue(sql.contains("CAST(source_type AS BINARY) DESC"));
            assertTrue(sql.contains("CAST(source_id AS BINARY) DESC LIMIT ?"));
            assertFalse(sql.contains(" OFFSET ")); assertFalse(sql.contains("instruction"));
            assertFalse(sql.contains("storage_uri")); assertFalse(sql.contains("lease_token"));
        }
    }
    @Test void privateCaseAndLegacyQueriesNeverMergeTaskExecutionsAndUseLatestRevision() {
        String sql = HallReadSql.page(Map.of("kind", "private", "view", "recent", "q", ""));
        assertTrue(sql.contains("link.revision_no=c.revision"));
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM hall_case_execution"));
        assertEquals(2, sql.split("CAST\\('PRIVATE' AS BINARY\\)", -1).length - 1);
        assertTrue(sql.contains("e.update_time"));
        assertTrue(sql.contains("CAST(link.owner_jiacn AS BINARY)"));
        assertTrue(sql.contains("CAST(e.owner_jiacn AS BINARY)"));
    }
    @Test void needsActionUsesOnlyEvidenceAndArchiveCannotBeQueriedAsSuccessfulEmpty() {
        String sql = HallReadSql.page(Map.of("kind", "task", "view", "needsAction", "q", ""));
        assertTrue(sql.contains("CAST('failed' AS BINARY)"));
        String review = HallReadSql.page(Map.of("kind", "task", "view", "needsAction", "formalEnabled", true));
        assertTrue(review.contains("CAST(w.owner_jiacn AS BINARY)"));
        assertTrue(review.contains("CAST(ownw.owner_jiacn AS BINARY)"));
        assertTrue(review.contains("w.lease_token IS NULL"));
        assertTrue(review.contains("agent_task_formal_delivery_item"));
        assertFalse(sql.contains("QUEUED")); assertFalse(sql.contains("completed'"));
        assertTrue(sql.contains("CAST(p.jiacn AS BINARY)"));
        assertTrue(HallReadSql.page(Map.of("kind", "task", "view", "archive")).contains("CAST('archived' AS BINARY)"));
        assertThrows(IllegalArgumentException.class,
                () -> HallReadSql.page(Map.of("kind", "draft", "view", "archive")));
    }
}
