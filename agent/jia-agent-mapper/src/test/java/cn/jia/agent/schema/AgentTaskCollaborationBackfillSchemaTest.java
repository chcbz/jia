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
    void dryRunIsReadOnlyAndSupportsFrozenLegacyShapes() throws IOException {
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");
        String executable = withoutLineComments(dryRun);

        assertFalse(Pattern.compile("(?m)^\\s*(insert|update|delete|replace|alter|create|drop|truncate)\\b")
                .matcher(executable).find());
        assertTrue(dryRun.contains("json_table("));
        assertTrue(dryRun.contains("source_format in ('plain', 'json_string')"));
        for (String path : List.of(
                "$.agentid", "$.agent_id", "$.assigneeagentid", "$.assignee_agent_id", "$.id",
                "$.agentids", "$.agent_ids", "$.assignees", "$.agents")) {
            assertTrue(dryRun.contains(path), path);
        }
        assertTrue(dryRun.contains("blocked_invalid_json"));
        assertTrue(dryRun.contains("blocked_unsupported_json_shape"));
        assertTrue(dryRun.contains("skipped_empty_assignee"));
    }

    @Test
    void resolutionUsesOnlyExactScopedA02RegistryAndAliasEvidence() throws IOException {
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");

        assertTrue(dryRun.contains("join agent_identity_registry"));
        assertTrue(dryRun.contains("join agent_identity_alias"));
        assertTrue(dryRun.contains("binary a.alias_type = binary 'legacy_agent_id'"));
        assertTrue(dryRun.contains("binary a.alias_status in (binary 'active', binary 'revoked')"));
        assertTrue(dryRun.contains("binary r.canonical_type in ("));
        assertTrue(dryRun.contains("binary r.lifecycle_status in ("));
        assertTrue(dryRun.contains("binary a.tenant_id = binary n.tenant_id"));
        assertTrue(dryRun.contains("binary a.client_id = binary n.client_id"));
        assertTrue(dryRun.contains("binary a.owner_jiacn = binary n.tenant_id"));
        assertTrue(dryRun.contains("blocked_identity_scope_mismatch"));
        assertTrue(dryRun.contains("blocked_identity_ambiguous"));
        assertTrue(dryRun.contains("blocked_alias_not_valid_at_assignment"));
        assertFalse(dryRun.contains("join agent_runtime"));
        assertFalse(dryRun.contains("join agent_persona"));
        assertFalse(dryRun.contains("persona_code"));
        assertFalse(dryRun.contains("display_name"));
        assertFalse(dryRun.contains("profile_id"));
    }

    @Test
    void applyIsGuardedTransactionalTaskAtomicAndIdempotent() throws IOException {
        String apply = readResource("db/task-collaboration-backfill.sql");

        assertTrue(apply.contains("@b09_approved_report_sha256 regexp binary"));
        assertTrue(apply.contains("get_lock(@b09_lock_name, 0)"));
        assertTrue(apply.contains("start transaction with consistent snapshot"));
        assertTrue(apply.contains("where task_resolution_status = 'eligible'"));
        assertTrue(apply.contains("and r.resolution_status = 'eligible'"));
        assertFalse(apply.contains("on duplicate key update id = id"));
        assertTrue(apply.contains("assignment_source"));
        assertTrue(apply.contains("'migration'"));
        assertTrue(apply.contains("canonical_agent_count = 1"));
        assertTrue(apply.contains("not exists (\n      select 1 from agent_task_work_item"));
        assertTrue(apply.contains("not exists (\n      select 1 from agent_task_member"));
        assertTrue(apply.contains("concat('b09-', lower(sha2(concat_ws"));
        assertTrue(apply.contains("binary n.reward_status = binary 'running' then 'working'"));
        assertTrue(apply.contains("binary n.reward_status in (binary 'running', binary 'reviewing', binary 'blocked') then 'blocked'"));
        assertTrue(apply.contains("review_multi_agent_work_item_required"));
        assertTrue(apply.contains("incompatible backfill issue table collation"));
        assertTrue(apply.contains("incompatible backfill issue audit/scope columns"));
        assertTrue(apply.contains("commit;"));
        assertTrue(apply.contains("release_lock(@b09_lock_name)"));

        String executable = withoutLineComments(apply);
        assertFalse(Pattern.compile("(?im)^\\s*(insert|replace)\\s+into\\s+agent_identity_")
                .matcher(executable).find());
        assertFalse(Pattern.compile("(?im)^\\s*update\\s+agent_task_meta\\b")
                .matcher(executable).find());
        assertFalse(Pattern.compile("(?im)^\\s*(insert|replace)\\s+into\\s+agent_runtime\\b")
                .matcher(executable).find());
    }


    @Test
    void dryRunAndApplyUseTheSameResolutionCte() throws IOException {
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");
        String apply = readResource("db/task-collaboration-backfill.sql");

        assertEquals(resolutionCte(dryRun), resolutionCte(apply));
    }

    @Test
    void persistedEnumsAndHistoricalStatusAreByteExactUnderAiCiDefaults() throws IOException {
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");
        String apply = readResource("db/task-collaboration-backfill.sql");
        String cte = resolutionCte(dryRun);

        assertTrue(cte.contains("binary n.reward_status in ("));
        assertTrue(cte.contains("binary n.reward_status = binary 'running'"));
        assertTrue(cte.contains("canonical_reward_status"));
        assertTrue(cte.contains("blocked_unsupported_task_status"));
        assertFalse(cte.contains("reward_status not in ("));
        assertFalse(apply.contains("case reward_status"));

        assertTrue(cte.contains("binary r.canonical_type in ("));
        assertTrue(cte.contains("binary r.lifecycle_status in ("));
        assertTrue(cte.contains("binary a.alias_type = binary 'legacy_agent_id'"));
        assertTrue(cte.contains("binary a.alias_status in (binary 'active', binary 'revoked')"));
        assertTrue(cte.contains("blocked_non_canonical_identity_record"));
    }

    @Test
    void aiCiEquivalentBusinessKeysAreAuditedAndNeverNoOpInserted() throws IOException {
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");
        String apply = readResource("db/task-collaboration-backfill.sql");
        String cte = resolutionCte(dryRun);

        assertTrue(cte.contains("existing_member_collation_conflicts"));
        assertTrue(cte.contains("existing_work_item_collation_conflicts"));
        assertTrue(cte.contains("collate utf8mb4_0900_ai_ci"));
        assertTrue(cte.contains("blocked_existing_member_collation_conflict"));
        assertTrue(cte.contains("blocked_existing_work_item_collation_conflict"));
        assertTrue(cte.contains("binary m.task_id = binary n.task_id"));
        assertTrue(cte.contains("binary m.agent_id = binary ic.canonical_agent_id"));
        assertTrue(cte.contains("binary w.work_item_id = binary n.planned_work_item_id"));
        assertFalse(apply.contains("on duplicate key update id = id"));
    }

    @Test
    void auditSchemaIsSynchronizedAndCarriesDeterministicEvidence() throws IOException {
        String schema = readResource("db/schema.sql");
        String apply = readResource("db/task-collaboration-backfill.sql");
        String expected = compact(tableDefinition(schema, "agent_task_backfill_issue"));

        assertEquals(expected, compact(tableDefinition(apply, "agent_task_backfill_issue")));
        assertTrue(expected.contains("unique key uk_task_backfill_issue_key (issue_key)"));
        assertTrue(expected.contains("first_report_sha256 char(64) not null"));
        assertTrue(expected.contains("last_report_sha256 char(64) not null"));
        assertTrue(expected.contains("occurrence_count bigint not null default 1"));
        assertTrue(expected.contains("tenant_id varchar(50) default null"));
        assertTrue(expected.contains("client_id varchar(50) default null"));
        assertTrue(expected.contains("utf8mb4_0900_bin"));
    }

    @Test
    void applyAvoidsMysqlFeaturesNewerThan8021() throws IOException {
        String apply = readResource("db/task-collaboration-backfill.sql");
        String dryRun = readResource("db/task-collaboration-backfill-dry-run.sql");
        String combined = apply + "\n" + dryRun;

        assertFalse(combined.contains("create trigger if not exists"));
        assertFalse(combined.contains("returning "));
        assertFalse(combined.contains("qualify "));
        assertFalse(combined.contains("json_value("));
        assertTrue(combined.contains("json_table("));
        assertTrue(apply.contains("values(last_report_sha256)"));
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
