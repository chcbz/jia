package cn.jia.agent.mapper;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HallReadMapperContractTest {
    @Test void disabledFormalTaskQueryPassesTheActualTenantInterceptorWithoutLosingScope() throws Exception {
        var policy = cn.jia.common.mybatis.TenantTablePolicy.of(
                java.util.List.of("agent_task_meta", "task_plan"), java.util.List.of());
        var handler = new com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler() {
            @Override public net.sf.jsqlparser.expression.Expression getTenantId() {
                return new net.sf.jsqlparser.expression.StringValue(
                        cn.jia.core.tenant.TrustedTenantContext.requireTenantId().value());
            }
            @Override public boolean ignoreTable(String table) { return !policy.isTenantTable(table); }
        };
        var constructor = Class.forName("cn.jia.common.mybatis.MysqlLockingTenantLineInnerInterceptor")
                .getDeclaredConstructor(com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler.class);
        constructor.setAccessible(true);
        var interceptor = (com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor) constructor.newInstance(handler);
        Configuration configuration = new Configuration(); configuration.addMapper(HallReadMapper.class);
        for (String tenant : java.util.List.of("0", "1")) {
            var scope = new cn.jia.core.tenant.BusinessScope(cn.jia.core.tenant.TenantId.of(tenant),
                    cn.jia.core.tenant.DataDomainId.of("cyf"), "owner", "client");
            for (String view : java.util.List.of("recent", "needsAction", "archive")) {
                var params = new HashMap<String,Object>(Map.of("tenantId",tenant,"clientId","client","ownerJiacn","owner",
                        "kind","task","view",view,"q","","limit",21,"formalEnabled",false));
                String sql = configuration.getMappedStatement(HallReadMapper.class.getName()+".page").getBoundSql(params).getSql();
                String rewritten = cn.jia.core.tenant.TrustedTenantContext.withScope(scope, () -> interceptor.parserSingle(sql, null));
                assertTrue(rewritten.contains("t.tenant_id = '"+tenant+"'"));
                assertTrue(rewritten.contains("p.tenant_id = '"+tenant+"'"));
                assertTrue(rewritten.contains("CAST(t.owner_jiacn AS BINARY)"));
                assertTrue(rewritten.contains("CAST(p.client_id AS BINARY)"));
            }
        }
    }
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
        assertFalse(sql.contains("QUEUED"));
        assertFalse(sql.substring(sql.indexOf(") hall WHERE")).contains("CAST('completed' AS BINARY)"));
        assertTrue(sql.contains("CAST(p.jiacn AS BINARY)"));
        assertTrue(HallReadSql.page(Map.of("kind", "task", "view", "archive")).contains("CAST('archived' AS BINARY)"));
        assertThrows(IllegalArgumentException.class,
                () -> HallReadSql.page(Map.of("kind", "draft", "view", "archive")));
    }
    @Test void overviewUsesExactlyTheBoardPhaseExpressionAndDisabledSchemaNeverQueriesDelivery() {
        String active=HallReadSql.page(Map.of("kind","task","view","recent","formalEnabled",true));
        assertTrue(active.contains(AgentTaskPhaseSql.PHASE.replace("task.","t.").replace("&gt;",">")));
        assertTrue(active.contains(" AS phase")); assertFalse(active.contains("&gt;"));
        String disabled=HallReadSql.page(Map.of("kind","task","view","recent","formalEnabled",false));
        assertFalse(disabled.contains("agent_task_formal_delivery")); assertTrue(disabled.contains("ELSE 'UNKNOWN' END AS phase"));
    }

}
