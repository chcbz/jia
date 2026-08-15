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
        assertTrue(sql.contains("concat('c01h-', lower(sha2(concat('cyf-c01h-event-id-v1', lpad(octet_length(cast(s.tenant_id as binary)), 10, '0')"));
        assertEquals(1, candidateCte(sql).split("'cyf-c01h-event-id-v1'", -1).length - 1);
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
        assertTrue(sql.contains("and ((binary e.task_id = binary c.task_id"));
        assertTrue(sql.contains("or (binary e.event_id = binary c.event_id"));
        assertTrue(sql.contains("as deterministic_event_count"));
        assertTrue(sql.contains("as baseline_type_count"));
        assertTrue(sql.contains("binary e.existing_task_id = binary e.task_id"));
        assertTrue(sql.contains("e.existing_create_time = e.b09_completed_at"));
        assertTrue(sql.contains("e.existing_update_time = e.b09_completed_at"));
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

        List<String> dryRunCandidates = candidateCtes(dryRun);
        assertEquals(2, dryRunCandidates.size());
        assertEquals(dryRunCandidates.get(0), dryRunCandidates.get(1));
        assertEquals(dryRunCandidates.get(0), candidateCte(manifest));
        assertEquals(dryRunCandidates.get(0), candidateCte(read("db/historical-task-event-baseline-routines.sql")));
        assertTrue(manifest.contains("order by binary tenant_id, binary client_id, binary task_id"));
        assertTrue(manifest.contains("c01h-manifest-chain-v1"));
        assertTrue(manifest.contains("c01h-manifest-final-v1"));
        assertTrue(manifest.contains("set export_row_count = export_row_count + 1"));
        assertFalse(manifest.contains("select count(*) from tmp_c01h_export_rows"));
        assertFalse(manifest.contains("group_concat("));
    }

    @Test
    void candidateCatalogProjectsSnapshotAliasesBeforeCanonicalRowDigest() throws IOException {
        String sql = exportSql();
        int candidates = sql.indexOf("candidate_rows as (");
        int taskSnapshot = sql.indexOf("c.task_version as task_version_snapshot", candidates);
        int eventSnapshot = sql.indexOf("c.current_event_version as current_event_version_snapshot", candidates);
        int manifestRows = sql.indexOf("manifest_rows as (", candidates);

        assertTrue(candidates >= 0 && candidates < taskSnapshot && taskSnapshot < eventSnapshot
                && eventSnapshot < manifestRows);
        assertFalse(candidateCte(sql).contains("group by binary"));
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

    @Test
    void auditSchemaHasThreeBinaryInnoDbTablesAndExactlyNineTamperTriggers() throws IOException {
        String audit = read("db/historical-task-event-baseline-audit-schema.sql");

        for (String table : List.of(
                "agent_task_historical_event_manifest_batch",
                "agent_task_historical_event_manifest",
                "agent_task_historical_event_run")) {
            String definition = tableDefinition(audit, table);
            assertTrue(definition.contains("engine=innodb"), table);
            assertTrue(definition.contains("collate=utf8mb4_0900_bin"), table);
            assertTrue(definition.contains("id bigint not null auto_increment"), table);
            assertTrue(definition.contains("primary key (id)"), table);
        }
        assertEquals(9, audit.split("create trigger trg_historical_event_", -1).length - 1);
        for (String trigger : List.of(
                "batch_insert_guard", "batch_update_guard", "batch_no_delete",
                "manifest_insert_guard", "manifest_no_update", "manifest_no_delete",
                "run_insert_guard", "run_no_update", "run_no_delete")) {
            assertTrue(audit.contains("trg_historical_event_" + trigger), trigger);
        }
        assertTrue(audit.contains("new.insert_required_count + new.exact_noop_count = new.manifest_row_count"));
        assertTrue(audit.contains("new.blocked_count = 0"));
        assertTrue(audit.contains("new.event_insert_count <> new.version_update_count"));
        assertTrue(audit.contains("new.event_insert_count + new.exact_noop_count <> new.manifest_row_count"));
    }

    @Test
    void approvalRecomputesBytesAndSealsOnlyPositiveZeroBlockedManifestsAtomically() throws IOException {
        String routines = read("db/historical-task-event-baseline-routines.sql");
        String approve = read("db/historical-task-event-baseline-approve.sql");

        assertTrue(approve.contains("call c01h_approve_manifest_atomic_v1("));
        assertTrue(routines.contains("staging_count=0"));
        assertTrue(routines.contains("zero candidates cannot be sealed"));
        assertTrue(routines.contains("hex(convert(unhex(tenant_id_hex) using utf8mb4)) <> tenant_id_hex"));
        assertTrue(routines.contains("cast(convert(unhex(b09_operator_hex) using utf8mb4) as char(100)) b09_operator"));
        assertTrue(routines.contains("cast(convert(unhex(tenant_id_hex) using utf8mb4) as char(50)) tenant_id"));
        assertTrue(routines.contains("cast(convert(unhex(task_id_hex) using utf8mb4) as char(100)) task_id"));
        assertTrue(routines.contains("binary manifest_row_key <> binary lower(sha2(concat('c01h-row-v1'"));
        assertTrue(routines.contains("binary event_id <> binary concat('c01h-', lower(sha2(concat('cyf-c01h-event-id-v1'"));
        assertTrue(routines.contains("binary manifest_row_sha256 <> binary lower(sha2(concat('c01h-manifest-row-v1'"));
        assertTrue(routines.contains("insert_count+noop_count<>verified_count"));
        assertTrue(routines.contains("blocked_count<>0"));
        assertTrue(routines.contains("start transaction;"));
        assertTrue(routines.contains("insert into agent_task_historical_event_manifest_batch"));
        assertTrue(routines.contains("insert into agent_task_historical_event_manifest("));
        assertTrue(routines.contains("seal_status='sealed'"));
        assertTrue(routines.contains("rollback;"));
        assertTrue(routines.contains("resignal;"));
    }

    @Test
    void approveAndApplyUseLockedDefinerAndFrozenGlobalLockOrder() throws IOException {
        String routines = read("db/historical-task-event-baseline-routines.sql");

        assertEquals(5, routines.split("create definer=`cyf_c01h_definer`@`localhost` procedure", -1).length - 1);
        assertEquals(5, routines.split("sql security definer", -1).length - 1);
        int approveLock = routines.indexOf("get_lock(lock_approve_name,0)");
        int backfillLock = routines.indexOf("get_lock(lock_backfill_name,0)", approveLock);
        int c01hLock = routines.indexOf("get_lock(lock_c01h_name,0)", backfillLock);
        assertTrue(approveLock >= 0 && approveLock < backfillLock && backfillLock < c01hLock);
        int secondApprove = routines.indexOf("get_lock(lock_approve_name,0)", c01hLock + 1);
        int secondBackfill = routines.indexOf("get_lock(lock_backfill_name,0)", secondApprove);
        int secondC01h = routines.indexOf("get_lock(lock_c01h_name,0)", secondBackfill);
        assertTrue(secondApprove > c01hLock && secondApprove < secondBackfill && secondBackfill < secondC01h);
        assertTrue(routines.contains("b09-manifest-approve:"));
        assertTrue(routines.contains("b09-task-backfill:"));
        assertTrue(routines.contains("c01h-historical-baseline:"));
        assertTrue(routines.contains("account lock"));
        assertTrue(routines.contains("grant create temporary tables, lock tables"));
        assertTrue(routines.contains("grant execute on procedure"));
        assertTrue(routines.contains("c01h_compute_verified_digest_v1 to `cyf_c01h_definer`@`localhost`"));
        assertTrue(routines.contains("c01h_build_current_snapshot_v1 to `cyf_c01h_definer`@`localhost`"));
        assertTrue(routines.contains("never grant operators direct dml"));
    }

    @Test
    void applyLocksAllRootsThenSnapshotsAndWritesEventCasRunInOneTransaction() throws IOException {
        String routines = read("db/historical-task-event-baseline-routines.sql");
        String apply = read("db/historical-task-event-baseline-apply.sql");

        assertTrue(apply.contains("call c01h_apply_manifest_atomic_v1("));
        int transaction = routines.lastIndexOf("start transaction;");
        int roots = routines.indexOf("open root_cur", transaction);
        int events = routines.indexOf("open event_cur", roots);
        int eventIds = routines.indexOf("open event_id_cur", events);
        int members = routines.indexOf("open member_cur", eventIds);
        int workItems = routines.indexOf("open work_cur", members);
        int current = routines.indexOf("call c01h_build_current_snapshot_v1", workItems);
        int eventInsert = routines.indexOf("insert into agent_task_event", current);
        int versionCas = routines.indexOf("update agent_task_meta set current_event_version", eventInsert);
        int runInsert = routines.indexOf("insert into agent_task_historical_event_run", versionCas);
        int commit = routines.indexOf("commit;", runInsert);
        assertTrue(transaction >= 0 && transaction < roots && roots < events && events < eventIds
                && eventIds < members && members < workItems && workItems < current && current < eventInsert
                && eventInsert < versionCas && versionCas < runInsert && runInsert < commit);
        assertTrue(routines.contains("order by binary m.tenant_id,binary m.client_id,binary m.task_id for update"));
        assertTrue(routines.contains("task_version=v_task_version and current_event_version=v_expected-1"));
        assertTrue(routines.contains("insert_count<>update_count or insert_count+noop_count<>sealed_count"));
        assertTrue(routines.contains("'historical_baseline_imported','system','c01h-b09','task'"));
        assertFalse(routines.contains("update agent_task_member"));
        assertFalse(routines.contains("update agent_task_work_item"));
        assertFalse(routines.contains("set task_version"));
    }

    @Test
    void repeatedApplyAllowsOnlyExactPostStateAndAddsOnlySuccessfulRun() throws IOException {
        String routines = read("db/historical-task-event-baseline-routines.sql");
        String apply = read("db/historical-task-event-baseline-apply.sql");

        assertTrue(apply.contains("repeating the same apply is an exact no-op"));
        assertTrue(routines.contains("binary a.decision_status=binary 'insert_required' and binary c.decision_status=binary 'exact_noop'"));
        assertTrue(routines.contains("c.current_event_version=a.current_event_version_snapshot+1"));
        assertTrue(routines.contains("c.event_chain_count=a.event_chain_count+1"));
        assertTrue(routines.contains("deterministic event id occupied or exact no-op evidence mismatch"));
        assertTrue(routines.contains("binary e.task_id=binary v_task and octet_length(e.task_id)=octet_length(v_task)"));
        assertTrue(routines.contains("e.event_version=v_expected"));
        assertTrue(routines.contains("binary e.event_type=binary 'historical_baseline_imported'"));
        assertTrue(routines.contains("binary e.actor_type=binary 'system' and binary e.actor_id=binary 'c01h-b09'"));
        assertTrue(routines.contains("binary e.aggregate_type=binary 'task'"));
        assertTrue(routines.contains("binary e.aggregate_id=binary v_task"));
        assertTrue(routines.contains("binary e.event_json=binary concat("));
        assertTrue(routines.contains("e.occurred_at=v_b09_completed and e.create_time=v_b09_completed and e.update_time=v_b09_completed"));
        assertTrue(routines.contains("binary hr.run_status=binary 'succeeded'"));
        assertTrue(routines.contains("hr.completed_at<=started"));
        assertTrue(routines.contains("set noop_count=noop_count+1"));
        assertTrue(routines.contains("'succeeded'"));
        assertFalse(routines.contains("'failed'"));
    }

    private String tableDefinition(String sql, String table) {
        int start = sql.indexOf("create table if not exists " + table + " (");
        assertTrue(start >= 0, table);
        int end = sql.indexOf(';', start);
        assertTrue(end > start, table);
        return sql.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    @Test
    void mysqlProbeIsolatedSafetyAndAdversarialCoverageAreFrozen() throws IOException {
        String probe = readFile("src/test/scripts/historical-task-event-baseline-mysql-probe.sh");

        assertTrue(probe.contains("--no-defaults"));
        assertTrue(probe.contains("historical-task-event-baseline-dry-run.sql"));
        assertTrue(probe.contains("execute_dry_run"));
        assertTrue(probe.contains("dry-run snapshot projection missing"));
        assertTrue(probe.contains("[[ \"$port\" == 33307 ]]"));
        assertTrue(probe.contains("[[ \"$datadir\" == /tmp/* ]]"));
        assertTrue(probe.contains("restricted operator unexpectedly performed direct dml"));
        assertTrue(probe.contains("c01h probe injected event failure"));
        assertTrue(probe.contains("--force"));
        assertTrue(probe.contains("force-continued-after-c01h-error"));
        assertTrue(probe.contains("version-drift"));
        assertTrue(probe.contains("partial-baseline"));
        assertTrue(probe.contains("event-id-preemption"));
        assertTrue(probe.contains("forged-same-task-event"));
        assertTrue(probe.contains("cyf-c01h-event-id-v1"));
        assertTrue(probe.contains("concurrent-writer"));
        assertTrue(probe.contains("named-lock"));
        assertTrue(probe.contains("task_version/member/work-item changed"));
        assertFalse(probe.contains(":3306"));
        assertFalse(probe.contains("/home/isp/apps/mysql/data"));
    }

    private String readFile(String path) throws IOException {
        return java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
    }

    private String exportSql() throws IOException {
        return read("db/historical-task-event-baseline-dry-run.sql");
    }

    private List<String> candidateCtes(String sql) {
        String begin = "-- c01h_candidate_cte_begin";
        String end = "-- c01h_candidate_cte_end";
        java.util.ArrayList<String> candidates = new java.util.ArrayList<>();
        int offset = 0;
        while (true) {
            int start = sql.indexOf(begin, offset);
            if (start < 0) {
                return candidates;
            }
            int finish = sql.indexOf(end, start);
            assertTrue(finish > start);
            candidates.add(sql.substring(start, finish + end.length()));
            offset = finish + end.length();
        }
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
