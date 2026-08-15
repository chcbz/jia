package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHistoricalTaskEventBaselineSchemaTest {

    @Test
    void exportConsumesOneExactSealedB09ReportAndMatchingSuccessfulRun() throws IOException {
        String sql = exportSql();

        assertTrue(sql.contains("binary b.report_sha256 = binary @c01h_b09_report_sha256"));
        assertTrue(sql.contains("binary b.seal_status = binary 'sealed'"));
        assertTrue(sql.contains("binary r.run_id = binary @c01h_b09_run_id"));
        assertTrue(sql.contains("binary r.operator = binary b.approved_operator"));
        assertTrue(sql.contains("binary r.run_status = binary 'succeeded'"));
        assertTrue(sql.contains("r.completed_at > 0"));
        assertTrue(sql.contains("having eligible_task_rows = b09_task_manifest_rows"));
        assertTrue(sql.contains("eligible_resolution_rows = b09_task_manifest_rows"));
    }

    @Test
    void scopeAndDeterministicIdUseByteExactLengthPrefixedEncodingWithoutDelimiters() throws IOException {
        String sql = exportSql();

        for (String field : List.of("tenant_id", "client_id", "task_id")) {
            assertTrue(sql.contains("binary m." + field + " = binary s." + field), field);
            assertTrue(sql.contains("octet_length(m." + field + ") = octet_length(s." + field + ")"), field);
        }
        assertTrue(sql.contains("concat('c01h-', lower(sha2(concat(lpad(octet_length(cast(s.tenant_id as binary)), 10, '0')"));
        assertTrue(sql.contains("lpad(octet_length(cast(s.client_id as binary)), 10, '0')"));
        assertTrue(sql.contains("lpad(octet_length(cast(s.task_id as binary)), 10, '0')"));
        assertFalse(sql.contains("concat_ws("));
        assertFalse(sql.contains("group_concat("));
        assertFalse(sql.contains("char(31)"));
    }

    @Test
    void decisionsPayloadAndVersionContractAreFrozen() throws IOException {
        String sql = exportSql();

        for (String decision : List.of("insert_required", "exact_noop", "blocked")) {
            assertTrue(sql.contains("'" + decision + "'"), decision);
        }
        assertTrue(sql.contains("'historical_baseline_imported'"));
        assertTrue(sql.contains("binary e.existing_actor_type = binary 'system'"));
        assertTrue(sql.contains("binary e.existing_actor_id = binary 'c01h-b09'"));
        assertTrue(sql.contains("binary e.existing_aggregate_type = binary 'task'"));
        assertTrue(sql.contains("c.current_event_version + 1"));
        assertTrue(sql.contains("e.current_event_version = 9223372036854775807"));
        assertTrue(sql.contains("e.event_chain_count = e.current_event_version"));
        assertTrue(sql.contains("e.event_chain_min_version = 1"));
        assertTrue(sql.contains("e.event_chain_max_version = e.current_event_version"));
        assertTrue(sql.contains("{\"contentsha256\":\""));
        assertTrue(sql.contains("\"decisioncode\":\"c01h_b09_v1\""));
        assertTrue(sql.contains("\"membercount\":"));
        assertTrue(sql.contains("\"source\":\"b09\""));
        assertTrue(sql.contains("\"workitemcount\":"));
        assertFalse(sql.contains("lease_token\":"));
    }

    @Test
    void contentSnapshotCoversTaskMembersWorkItemsAndFailsClosedOnLeases() throws IOException {
        String sql = exportSql();

        assertTrue(sql.contains("'c01h-meta-v1'"));
        assertTrue(sql.contains("'c01h-member-v1'"));
        assertTrue(sql.contains("'c01h-work-item-v1'"));
        assertTrue(sql.contains("'c01h-content-v1'"));
        assertTrue(sql.contains("x.lease_token is not null or x.lease_until is not null"));
        assertTrue(sql.contains("binary x.status in (binary 'claimed', binary 'running')"));
        assertTrue(sql.contains("e.member_count <= 0 or e.work_item_count <= 0"));
        assertTrue(sql.contains("e.lease_blocked_count <> 0"));
        assertTrue(sql.contains("agent_task_historical_event_manifest hm"));
        assertTrue(sql.contains("binary hr.run_status = binary 'succeeded'"));
    }

    @Test
    void dryRunAndManifestShareOneByteExactCandidateCatalog() throws IOException {
        String dryRun = read("db/historical-task-event-baseline-dry-run.sql");
        String manifest = read("db/historical-task-event-baseline-manifest.sql");

        assertEquals(candidateCte(dryRun), candidateCte(manifest));
        assertTrue(manifest.contains("order by binary tenant_id, binary client_id, binary task_id"));
        assertTrue(manifest.contains("c01h-manifest-chain-v1"));
        assertTrue(manifest.contains("c01h-manifest-final-v1"));
        assertFalse(manifest.contains("group_concat("));
    }

    @Test
    void stagingUsesOnlyLongTextAndCannotCoerceReviewedBytes() throws IOException {
        String staging = read("db/historical-task-event-baseline-staging.sql");

        assertTrue(staging.contains("create temporary table tmp_c01h_approved_manifest_staging"));
        assertEquals(23, staging.split("longtext null", -1).length - 1);
        assertFalse(staging.contains("varchar("));
        assertFalse(staging.contains("bigint"));
        for (String column : List.of(
                "tenant_id_hex", "client_id_hex", "task_id_hex", "b09_operator_hex",
                "decision_status", "content_sha256", "baseline_event_version_value")) {
            assertTrue(staging.contains(column), column);
        }
    }

    private String exportSql() throws IOException {
        return read("db/historical-task-event-baseline-dry-run.sql");
    }

    private String candidateCte(String sql) {
        String begin = "-- c01h_candidate_cte_begin";
        String end = "-- c01h_candidate_cte_end";
        int start = sql.indexOf(begin);
        int finish = sql.indexOf(end, start);
        assertTrue(start >= 0 && finish > start);
        return sql.substring(start, finish + end.length());
    }

    private String read(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }
}
