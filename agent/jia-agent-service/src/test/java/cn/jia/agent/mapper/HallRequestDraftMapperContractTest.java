package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HallRequestDraftMapperContractTest {
    @Test
    void listEditingFirstPageBoundSqlSeparatesSelectFromProjection() throws Exception {
        BoundSql bound = listEditingBoundSql(null, null);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.startsWith("SELECT draft_id,"), sql);
        assertTrue(sql.contains(" FROM hall_request_draft "), sql);
        assertTrue(sql.endsWith("ORDER BY updated_at DESC, CAST(draft_id AS BINARY) DESC LIMIT ?"), sql);
        assertFalse(sql.contains("updated_at < ?"), sql);
        List<String> parameters = bound.getParameterMappings().stream()
                .map(ParameterMapping::getProperty).toList();
        assertFalse(parameters.contains("beforeUpdatedAt"));
        assertFalse(parameters.contains("beforeDraftId"));
        assertTrue(parameters.containsAll(List.of("tenantId", "clientId", "ownerJiacn", "limit")));
    }

    @Test
    void listEditingCursorBoundSqlKeepsParameterizedExactScopedSeek() throws Exception {
        BoundSql bound = listEditingBoundSql(1_790_000_000_000L, "draft-cursor");
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.startsWith("SELECT draft_id,"), sql);
        assertTrue(sql.contains("updated_at < ?"), sql);
        assertTrue(sql.contains("CAST(draft_id AS BINARY) < CAST(? AS BINARY)"), sql);
        for (String column : List.of("tenant_id", "client_id", "owner_jiacn")) {
            assertTrue(sql.contains("CAST(" + column + " AS BINARY)=CAST(? AS BINARY)"), sql);
            assertTrue(sql.contains("OCTET_LENGTH(" + column + ")=OCTET_LENGTH(?)"), sql);
        }
        List<String> parameters = bound.getParameterMappings().stream()
                .map(ParameterMapping::getProperty).toList();
        assertEquals(2L, parameters.stream().filter("beforeUpdatedAt"::equals).count());
        assertEquals(1L, parameters.stream().filter("beforeDraftId"::equals).count());
        assertFalse(sql.contains("draft-cursor"), "cursor value must remain JDBC-bound");
    }

    private static BoundSql listEditingBoundSql(Long beforeUpdatedAt, String beforeDraftId)
            throws Exception {
        Method target = HallRequestDraftMapper.class.getMethod("listEditing", String.class,
                String.class, String.class, Long.class, String.class, int.class);
        String script = String.join(" ", target.getAnnotation(Select.class).value());
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", "0");
        parameters.put("clientId", "client-a");
        parameters.put("ownerJiacn", "owner-a");
        parameters.put("beforeUpdatedAt", beforeUpdatedAt);
        parameters.put("beforeDraftId", beforeDraftId);
        parameters.put("limit", 21);
        return new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class)
                .getBoundSql(parameters);
    }

    @Test
    void everyObjectReadUsesExactTenantClientOwnerPredicates() throws Exception {
        for (String method : new String[] {"findExact", "lockExact", "findByCreateKey", "findBySubmitKey",
                    "findByDiscardKey", "listEditing"}) {
            Method target = Arrays.stream(HallRequestDraftMapper.class.getMethods())
                    .filter(candidate -> method.equals(candidate.getName())).findFirst().orElseThrow();
            Select select = target.getAnnotation(Select.class);
            String sql = String.join(" ", select.value());
            assertTrue(sql.contains("CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)"), method);
            assertTrue(sql.contains("CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)"), method);
            assertTrue(sql.contains("CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)"), method);
        }
    }

    @Test
    void privateRevisionSourceRequiresExactOwnerCommittedPrivateOutputAndActiveFile()
            throws Exception {
        Method target = HallRequestDraftMapper.class.getMethod("countPrivateCommittedOutput",
                String.class, String.class, String.class, String.class, String.class,
                String.class, int.class);
        String sql = String.join(" ", target.getAnnotation(Select.class).value());
        assertTrue(sql.contains("e.execution_mode='PRIVATE'"));
        assertTrue(sql.contains("e.execution_state='OUTPUT_COMMITTED'"));
        assertTrue(sql.contains("o.output_state='COMMITTED'"));
        assertTrue(sql.contains("o.publication_state='PENDING'"));
        assertTrue(sql.contains("f.state='ACTIVE'"));
        assertTrue(sql.contains("CAST(e.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)"));
    }

    @Test
    void createUsesUniqueReservationAndMutationsAreSingleStatementCas() throws Exception {
        Insert create = HallRequestDraftMapper.class.getMethod("reserveCreate",
                cn.jia.agent.entity.HallRequestDraftEntity.class).getAnnotation(Insert.class);
        assertTrue(String.join(" ", create.value()).contains("ON DUPLICATE KEY UPDATE draft_id=draft_id"));


        Method reserve = HallRequestDraftMapper.class.getMethod("reserveSubmitIntent",
                String.class, String.class, String.class, String.class, long.class,
                String.class, String.class, long.class);
        String reserveSql = String.join(" ", reserve.getAnnotation(Update.class).value());
        assertTrue(reserveSql.contains("UPDATE hall_request_draft"));
        assertTrue(!reserveSql.contains("IGNORE"),
                "a matched-row count must not hide a duplicate submit-key reservation");
        assertTrue(reserveSql.contains("submit_key IS NULL AND submit_hash IS NULL"));
        for (String method : new String[] {"replaceEditing", "markSubmitted", "discardEditing"}) {
            Method target = Arrays.stream(HallRequestDraftMapper.class.getMethods())
                    .filter(candidate -> method.equals(candidate.getName())).findFirst().orElseThrow();
            String sql = String.join(" ", target.getAnnotation(Update.class).value());
            assertTrue(sql.contains("state='EDITING' AND revision=#{expectedRevision}"), method);
            assertTrue(sql.contains("revision=revision+1"), method);
            assertTrue(sql.contains("CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)"), method);
        }
    }

    @Test
    void resultIndexIsPrivateExactScopedAndSensitiveColumnAllowlisted() throws Exception {
        Method target = HallRequestDraftMapper.class.getMethod("listPrivateExecutionResults",
                String.class, String.class, String.class, String.class);
        String sql = String.join(" ", target.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("e.execution_mode='private'"));
        for (String identity : new String[] {"e.tenant_id", "e.client_id", "e.owner_jiacn",
                "e.execution_id"}) {
            assertTrue(sql.contains("cast(" + identity + " as binary)=cast(#{"), identity);
            assertTrue(sql.contains("octet_length(" + identity + ")=octet_length(#{"), identity);
        }
        String projection = sql.substring(0, sql.indexOf(" from "));
        for (String sensitive : new String[] {"storage_uri", "instruction", "lease_token",
                "idempotency_key", "request_hash", "failure_message"}) {
            assertTrue(!projection.contains(sensitive), sensitive);
        }
        assertTrue(sql.contains("v.version=o.workspace_file_version"));
        assertTrue(sql.contains("order by cast(o.output_id as binary) asc"));
    }
}
