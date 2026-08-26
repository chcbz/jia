package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskCollaborationBackfillSchemaTest {

    @Test
    void dryRunIsReadOnlyAndRejectsWhitespaceWrongTypesAndAmbiguousTargets() throws IOException {
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");
        String executable = withoutLineComments(dryRun);

        assertFalse(Pattern.compile("(?m)^\\s*(insert|update|delete|replace|alter|create|drop|truncate)\\b")
                .matcher(executable).find());
        assertTrue(dryRun.contains("json_table("));
        for (String path : List.of(
                "$.agentid", "$.agent_id", "$.assigneeagentid", "$.assignee_agent_id", "$.id",
                "$.agentids", "$.agent_ids", "$.assignees", "$.agents")) {
            assertTrue(dryRun.contains(path), path);
        }
        assertTrue(dryRun.contains("direct_present_count"));
        assertTrue(dryRun.contains("wrapper_present_count"));
        assertTrue(dryRun.contains("blocked_invalid_json_target_type"));
        assertTrue(dryRun.contains("blocked_ambiguous_json_object"));
        assertTrue(dryRun.contains("blocked_agent_id_boundary_whitespace"));
        assertTrue(dryRun.contains("binary c.candidate_agent_id <> binary trim(c.candidate_agent_id)"));
        assertFalse(dryRun.contains("nullif(trim(c.candidate_agent_id)"));
    }

    @Test
    void resolutionUsesOnlyByteExactScopedA02EvidenceAndFrozenStatuses() throws IOException {
        String cte = resolutionCte(readResource("db/task-collaboration-backfill-dry-run.sql"));

        assertTrue(cte.contains("binary a.alias_type = binary 'legacy_agent_id'"));
        assertTrue(cte.contains("binary a.alias_status in (binary 'active', binary 'revoked')"));
        assertTrue(cte.contains("binary r.canonical_type in ("));
        assertTrue(cte.contains("binary r.lifecycle_status in ("));
        assertTrue(cte.contains("binary a.tenant_id = binary n.tenant_id"));
        assertTrue(cte.contains("binary a.client_id = binary n.client_id"));
        assertTrue(cte.contains("binary n.reward_status = binary 'running'"));
        assertTrue(cte.contains("blocked_unsupported_task_status"));
        assertTrue(cte.contains("blocked_non_canonical_identity_record"));
        assertFalse(cte.contains("reward_status not in ("));
        assertFalse(cte.contains("join agent_runtime"));
        assertFalse(cte.contains("display_name"));
    }

    @Test
    void dryRunManifestAndApplyUseTheSameResolutionCte() throws IOException {
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");
        String manifest = readResource("db/task-collaboration-backfill-manifest.sql");
        String routines = readResource("db/task-collaboration-backfill-routines.sql");

        assertEquals(resolutionCte(dryRun), resolutionCte(manifest));
        assertEquals(resolutionCte(dryRun), resolutionCte(routines));
        assertTrue(resolutionCte(dryRun).contains("manifest_rows as ("));
        assertTrue(resolutionCte(dryRun).contains("b09-manifest-content-v2"));
    }

    @Test
    void canonicalManifestDigestIsDatabaseRecomputedOrderedAndUntruncated() throws IOException {
        String manifest = readResource("db/task-collaboration-backfill-manifest.sql");
        String staging = readResource("db/task-collaboration-backfill-staging.sql");
        String routines = readResource("db/task-collaboration-backfill-routines.sql");

        assertTrue(manifest.contains("b09-manifest-batch-chain-v2"));
        assertTrue(manifest.contains("b09-manifest-batch-final-v2"));
        assertTrue(manifest.contains("order by binary manifest_row_key"));
        String digestSection = manifest.substring(manifest.indexOf("drop procedure if exists b09_compute_export_manifest_digest_v3"));
        assertFalse(digestSection.contains("group_concat("));
        assertFalse(routines.contains("group_concat("));
        assertTrue(routines.contains("canonical digest mismatch"));
        assertTrue(routines.contains("row key or row digest is forged"));
        assertTrue(routines.contains("binary computed_digest <> binary approved_manifest_digest"));
        assertTrue(routines.contains("sealed manifest canonical digest mismatch"));
        assertTrue(routines.contains("sealed manifest row count mismatch"));
        assertTrue(staging.contains("task_id_hex                     longtext null"));
        assertTrue(staging.contains("required_abilities_hex          longtext null"));
        assertFalse(staging.contains("varchar("));
        assertTrue(routines.contains("octet_length(unhex(task_id_hex)) > 400"));
        assertTrue(routines.contains("hex(convert(unhex(task_id_hex) using utf8mb4))"));
    }

    @Test
    void sealedManifestRejectsAppendUpdateDeleteAndApplyRejectsDrift() throws IOException {
        String audit = readResource("db/task-collaboration-backfill-audit-schema.sql");
        String routines = readResource("db/task-collaboration-backfill-routines.sql");

        assertTrue(routines.contains("insert into agent_task_backfill_manifest_batch"));
        assertTrue(routines.contains("'loading'"));
        assertTrue(routines.contains("seal_status = 'sealed'"));
        assertTrue(audit.contains("trg_task_backfill_manifest_batch_insert_guard"));
        assertTrue(audit.contains("trg_task_backfill_manifest_insert_guard"));
        assertTrue(audit.contains("procedure-owned loading batch"));
        assertTrue(audit.contains("trg_task_backfill_issue_insert_guard"));
        assertTrue(audit.contains("trg_task_backfill_run_insert_guard"));
        assertTrue(audit.contains("matching sealed approval"));
        assertTrue(audit.contains("complete matching sealed approval"));
        assertTrue(routines.contains("current task row count differs from sealed manifest"));
        assertTrue(routines.contains("current source/scope/resolution differs from sealed manifest"));
        assertTrue(routines.contains("a sealed source row was deleted or changed"));
        assertTrue(routines.contains("approved.manifest_row_sha256 = binary current_row.manifest_row_sha256"));
    }

    @Test
    void approvalAndApplyAreSingleAtomicCallsSafeUnderMysqlForce() throws IOException {
        String approve = readResource("db/task-collaboration-backfill-approve.sql");
        String apply = readResource("db/task-collaboration-backfill.sql");
        String routines = readResource("db/task-collaboration-backfill-routines.sql");

        assertTrue(routines.contains("declare exit handler for sqlexception"));
        assertTrue(routines.contains("rollback;"));
        assertTrue(routines.contains("resignal;"));
        assertTrue(routines.contains("create definer=`cyf_b09_definer`@`localhost` procedure"));
        assertEquals(4, routines.split("sql security definer", -1).length - 1);
        assertTrue(approve.contains("call b09_approve_manifest_atomic_v4("));
        assertTrue(apply.contains("call b09_apply_manifest_atomic_v4("));
        assertFalse(approve.contains("create procedure"));
        assertFalse(apply.contains("create procedure"));
        assertTrue(routines.contains("insert into agent_task_backfill_run"));
        assertTrue(routines.indexOf("insert into agent_task_backfill_run") < routines.lastIndexOf("commit;"));
        assertTrue(routines.contains("regexp_like(applying_operator, '[[:cntrl:]]', 'c')"));
        assertTrue(routines.contains("regexp_like(approving_operator, '[[:cntrl:]]', 'c')"));
    }

    @Test
    void applyIsTransactionalTaskAtomicAndBusinessIdempotent() throws IOException {
        String routines = readResource("db/task-collaboration-backfill-routines.sql");

        assertTrue(routines.contains("get_lock(lock_name, 0)"));
        assertTrue(routines.contains("start transaction with consistent snapshot"));
        assertTrue(routines.contains("where r.task_resolution_status = 'eligible'"));
        assertTrue(routines.contains("and r.resolution_status = 'eligible'"));
        assertTrue(routines.contains("not exists (\n      select 1 from agent_task_work_item"));
        assertTrue(routines.contains("not exists (\n      select 1 from agent_task_member"));
        assertFalse(routines.contains("on duplicate key update id = id"));
        assertFalse(Pattern.compile("(?im)^\\s*(insert|replace)\\s+into\\s+agent_identity_")
                .matcher(withoutLineComments(routines)).find());
        assertFalse(Pattern.compile("(?im)^\\s*update\\s+agent_task_meta\\b")
                .matcher(withoutLineComments(routines)).find());
    }

    @Test
    void auditSchemaIsSynchronizedForIssueBatchManifestAndRun() throws IOException {
        String schema = readResource("db/schema.sql");
        String audit = readResource("db/task-collaboration-backfill-audit-schema.sql");

        for (String table : List.of(
                "agent_task_backfill_issue", "agent_task_backfill_manifest_batch",
                "agent_task_backfill_manifest", "agent_task_backfill_run")) {
            String expected = compact(tableDefinition(schema, table));
            assertEquals(expected, compact(tableDefinition(audit, table)), table);
            assertTrue(expected.contains("utf8mb4_0900_bin"), table);
            assertTrue(expected.contains("id bigint not null auto_increment"), table);
            assertTrue(expected.contains("primary key (id)"), table);
        }
        assertTrue(schema.contains("unique key uk_task_backfill_manifest_batch_digest (report_sha256)"));
        assertTrue(schema.contains("unique key uk_task_backfill_manifest_row (report_sha256, manifest_row_key)"));
        assertTrue(schema.contains("unique key uk_task_backfill_run_id (run_id)"));
        assertTrue(compact(schema).contains("issue_row_count bigint not null default 0"));
        for (String table : List.of("agent_task_backfill_issue", "agent_task_backfill_manifest")) {
            String definition = compact(tableDefinition(schema, table));
            assertTrue(definition.contains("tenant_id varchar(50) default null"), table);
            assertFalse(definition.contains("tenant_id varchar(50) default '0'"), table);
        }
    }

    @Test
    void aiCiEquivalentBusinessKeysAreAuditedAndNeverNoOpInserted() throws IOException {
        String cte = resolutionCte(readResource("db/task-collaboration-backfill-dry-run.sql"));
        String routines = readResource("db/task-collaboration-backfill-routines.sql");

        assertTrue(cte.contains("existing_member_collation_conflicts"));
        assertTrue(cte.contains("existing_work_item_collation_conflicts"));
        assertTrue(cte.contains("collate utf8mb4_0900_ai_ci"));
        assertTrue(cte.contains("blocked_existing_member_collation_conflict"));
        assertTrue(cte.contains("blocked_existing_work_item_collation_conflict"));
        assertFalse(routines.contains("on duplicate key update id = id"));
    }

    @Test
    void applyValidatesIndexesStructurallyWithoutGroupConcat() throws IOException {
        String routines = readResource("db/task-collaboration-backfill-routines.sql");
        assertFalse(routines.contains("group_concat("));
        assertTrue(routines.contains("seq_in_index = 4 and binary column_name = binary 'agent_id'"));
        assertTrue(routines.contains("seq_in_index = 3 and binary column_name = binary 'work_item_id'"));
        assertTrue(routines.contains("sub_part is null"));
        assertTrue(routines.contains("index_type = 'btree'"));
        assertTrue(routines.contains("is_visible = 'yes'"));
    }

    @Test
    void scriptsAvoidMysqlFeaturesNewerThan8021() throws IOException {
        String combined = readResource("db/task-collaboration-backfill.sql")
                + readResource("db/task-collaboration-backfill-dry-run.sql")
                + readResource("db/task-collaboration-backfill-manifest.sql")
                + readResource("db/task-collaboration-backfill-approve.sql")
                + readResource("db/task-collaboration-backfill-routines.sql")
                + readResource("db/task-collaboration-backfill-audit-schema.sql");

        assertFalse(withoutLineComments(combined).contains("create trigger if not exists"));
        assertFalse(combined.contains("returning "));
        assertFalse(combined.contains("qualify "));
        assertFalse(combined.contains("json_value("));
        assertTrue(combined.contains("json_table("));
    }

    private String resolutionCte(String sql) {
        String begin = "-- b09_resolution_cte_begin";
        String end = "-- b09_resolution_cte_end";
        int start = sql.indexOf(begin);
        assertTrue(start >= 0, begin);
        int finish = sql.indexOf(end, start);
        assertTrue(finish > start, end);
        return sql.substring(start, finish + end.length());
    }

    private String readResource(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }

    private String tableDefinition(String sql, String table) {
        int start = sql.indexOf("create table if not exists " + table + " (");
        assertTrue(start >= 0, table);
        int end = sql.indexOf(';', start);
        assertTrue(end > start, table);
        return sql.substring(start, end);
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String withoutLineComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "");
    }
}
