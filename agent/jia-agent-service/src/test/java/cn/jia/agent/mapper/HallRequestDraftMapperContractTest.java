package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HallRequestDraftMapperContractTest {
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
        assertTrue(reserveSql.contains("UPDATE IGNORE hall_request_draft"));
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
