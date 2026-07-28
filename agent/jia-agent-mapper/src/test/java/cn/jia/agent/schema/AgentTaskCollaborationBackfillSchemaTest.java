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
        String apply = readResource("db/task-collaboration-backfill.sql");

        assertEquals(resolutionCte(dryRun), resolutionCte(manifest));
        assertEquals(resolutionCte(dryRun), resolutionCte(apply));
        assertTrue(resolutionCte(dryRun).contains("manifest_rows as ("));
        assertTrue(resolutionCte(dryRun).contains("b09-manifest-content-v1"));
    }

    @Test
    void approvedManifestIsLineBasedImmutableAndApplyRejectsAnyDrift() throws IOException {
        String staging = readResource("db/task-collaboration-backfill-staging.sql");
        String approve = readResource("db/task-collaboration-backfill-approve.sql");
        String audit = readResource("db/task-collaboration-backfill-audit-schema.sql");
        String apply = readResource("db/task-collaboration-backfill.sql");

        assertTrue(staging.contains("create temporary table tmp_b09_approved_manifest_staging"));
        assertTrue(approve.contains("insert into agent_task_backfill_manifest"));
        assertTrue(approve.contains("staging contains duplicate manifest row keys"));
        assertTrue(audit.contains("trg_task_backfill_manifest_no_update"));
        assertTrue(audit.contains("trg_task_backfill_manifest_no_delete"));
        assertTrue(apply.contains("current task row count differs from approved manifest"));
        assertTrue(apply.contains("current task source/scope/resolution differs from approved manifest"));
        assertTrue(apply.contains("an approved task row was deleted or changed"));
        assertTrue(apply.contains("approved.manifest_row_sha256 = binary current_row.manifest_row_sha256"));
        assertTrue(apply.contains("binary approved.tenant_id <=> binary current_row.tenant_id"));
        assertTrue(apply.contains("binary approved.resolution_status = binary current_row.resolution_status"));
    }

    @Test
    void operatorGateRejectsControlCharactersAndSuccessfulRunsAreAlwaysAudited() throws IOException {
        String approve = readResource("db/task-collaboration-backfill-approve.sql");
        String apply = readResource("db/task-collaboration-backfill.sql");

        assertTrue(approve.contains("regexp_like(@b09_operator, '[[:cntrl:]]', 'c')"));
        assertTrue(apply.contains("regexp_like(@b09_operator, '[[:cntrl:]]', 'c')"));
        assertTrue(apply.contains("apply operator does not match the immutable approval"));
        assertTrue(apply.contains("insert into agent_task_backfill_run"));
        assertTrue(apply.contains("'succeeded'"));
        assertTrue(apply.contains("@b09_issue_row_count"));
        assertTrue(apply.indexOf("insert into agent_task_backfill_run") < apply.indexOf("commit;"));
    }

    @Test
    void applyIsTransactionalTaskAtomicAndBusinessIdempotent() throws IOException {
        String apply = readResource("db/task-collaboration-backfill.sql");

        assertTrue(apply.contains("get_lock(@b09_lock_name, 0)"));
        assertTrue(apply.contains("start transaction with consistent snapshot"));
        assertTrue(apply.contains("where r.task_resolution_status = 'eligible'"));
        assertTrue(apply.contains("and r.resolution_status = 'eligible'"));
        assertTrue(apply.contains("not exists (\n      select 1 from agent_task_work_item"));
        assertTrue(apply.contains("not exists (\n      select 1 from agent_task_member"));
        assertFalse(apply.contains("on duplicate key update id = id"));
        assertFalse(Pattern.compile("(?im)^\\s*(insert|replace)\\s+into\\s+agent_identity_")
                .matcher(withoutLineComments(apply)).find());
        assertFalse(Pattern.compile("(?im)^\\s*update\\s+agent_task_meta\\b")
                .matcher(withoutLineComments(apply)).find());
    }

    @Test
    void auditSchemaIsSynchronizedForIssueManifestAndRun() throws IOException {
        String schema = readResource("db/schema.sql");
        String audit = readResource("db/task-collaboration-backfill-audit-schema.sql");

        for (String table : List.of(
                "agent_task_backfill_issue", "agent_task_backfill_manifest", "agent_task_backfill_run")) {
            String expected = compact(tableDefinition(schema, table));
            assertEquals(expected, compact(tableDefinition(audit, table)), table);
            assertTrue(expected.contains("utf8mb4_0900_bin"), table);
        }
        assertTrue(schema.contains("unique key uk_task_backfill_manifest_row (report_sha256, manifest_row_key)"));
        assertTrue(schema.contains("unique key uk_task_backfill_run_id (run_id)"));
        assertTrue(compact(schema).contains("issue_row_count bigint not null default 0"));
    }

    @Test
    void aiCiEquivalentBusinessKeysAreAuditedAndNeverNoOpInserted() throws IOException {
        String cte = resolutionCte(readResource("db/task-collaboration-backfill-dry-run.sql"));
        String apply = readResource("db/task-collaboration-backfill.sql");

        assertTrue(cte.contains("existing_member_collation_conflicts"));
        assertTrue(cte.contains("existing_work_item_collation_conflicts"));
        assertTrue(cte.contains("collate utf8mb4_0900_ai_ci"));
        assertTrue(cte.contains("blocked_existing_member_collation_conflict"));
        assertTrue(cte.contains("blocked_existing_work_item_collation_conflict"));
        assertFalse(apply.contains("on duplicate key update id = id"));
    }

    @Test
    void scriptsAvoidMysqlFeaturesNewerThan8021() throws IOException {
        String combined = readResource("db/task-collaboration-backfill.sql")
                + readResource("db/task-collaboration-backfill-dry-run.sql")
                + readResource("db/task-collaboration-backfill-manifest.sql")
                + readResource("db/task-collaboration-backfill-approve.sql")
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
        int start = sql.indexOf("create table if not exists " + table);
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
