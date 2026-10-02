package cn.jia.chat.archive.maintenance.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveMaintenanceSchemaCatalogTest {
    @Test
    void parsesAllAdditiveTablesIncludingEveryColumnAndConstraint() {
        var expected = ArchiveMaintenanceSchemaCatalog.expected();
        assertEquals(ArchiveMaintenanceSchemaCatalog.TABLE_ORDER.size(), expected.tables().size());
        var draft = expected.tables().get("archive_draft");
        assertEquals(9, draft.columns().size());
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("longtext", false, "utf8mb4_0900_bin"),
                draft.columns().get("content_json"));
        assertEquals("0:job_id", draft.indexes().get("uk_archive_draft_job"));
        assertEquals("job_id>archive_maintenance_job.job_id", draft.foreignKeys().get("fk_archive_draft_job"));
        assertTrue(draft.checks().get("chk_archive_draft_state").startsWith("YES:"));
        var withdrawal = expected.tables().get("archive_edition_withdrawal");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("varchar(1000)", false,
                "utf8mb4_0900_bin"), withdrawal.columns().get("reason"));
        assertEquals("0:publication_id", withdrawal.indexes().get("uk_archive_withdrawal_publication"));
        assertEquals("publication_id>archive_publication.publication_id",
                withdrawal.foreignKeys().get("fk_archive_withdrawal_publication"));
        assertTrue(withdrawal.checks().get("chk_archive_withdrawal_outbox").contains("PENDING"));
        assertEquals("YES:actor_type='HUMAN'",
                withdrawal.checks().get("chk_archive_withdrawal_actor"));
        assertEquals(ArchiveMaintenanceSchemaCatalog.normalizeCheck("(actor_type = 'HUMAN')"),
                ArchiveMaintenanceSchemaCatalog.normalizeCheck("(actor_type = _utf8mb4\\'HUMAN\\')"));
        var adminOperation = expected.tables().get("archive_admin_operation_receipt");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("longtext", true,
                "utf8mb4_0900_bin"), adminOperation.columns().get("result_json"));
        assertEquals("0:tenant_id,client_id,owner_jiacn,operation_key",
                adminOperation.indexes().get("uk_archive_admin_operation_key"));
        assertEquals("tenant_id>archive_operation.tenant_id,client_id>archive_operation.client_id,"
                + "owner_jiacn>archive_operation.owner_jiacn,operation_key>archive_operation.operation_key",
                adminOperation.foreignKeys().get("fk_archive_admin_operation_key"));
        assertTrue(adminOperation.checks().get("chk_archive_admin_operation_state")
                .contains("result_jsonisnotnull"));
        var businessOutbox = expected.tables().get("archive_business_outbox");
        assertEquals("1:state,available_at,projection_key",
                businessOutbox.indexes().get("idx_archive_business_available"));
        assertEquals("1:state,lease_until,projection_key",
                businessOutbox.indexes().get("idx_archive_business_lease"));
        assertEquals("job_id>archive_event.job_id,event_sequence>archive_event.sequence",
                businessOutbox.foreignKeys().get("fk_archive_business_event"));
        assertTrue(businessOutbox.checks().get("chk_archive_business_source")
                .contains("event_sequenceisnotnull"));
        assertTrue(businessOutbox.checks().get("chk_archive_business_state")
                .contains("NO_TARGET"));
        var event = expected.tables().get("archive_event");
        assertEquals("0:job_id,sequence", event.indexes().get("PRIMARY"));
        assertEquals("job_id>archive_maintenance_job.job_id", event.foreignKeys().get("fk_archive_event_job"));
        assertEquals("1:work_id", expected.tables().get("archive_collection_work").indexes()
                .get("fk_archive_collection_work_work"));
        assertEquals("1:appointment_id", expected.tables().get("archive_maintenance_job").indexes()
                .get("fk_archive_job_appointment"));
        assertEquals("1:source_id", expected.tables().get("archive_maintenance_job").indexes()
                .get("fk_archive_job_source"));
        assertEquals("1:collection_id", expected.tables().get("archive_publication").indexes()
                .get("fk_archive_publication_collection"));
        var readback = expected.tables().get("archive_publication_readback");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("char(64)", true, "ascii_bin"),
                readback.columns().get("verification_digest"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("timestamp(6)", true, null),
                readback.columns().get("checked_at"));
        assertEquals("publication_id>archive_publication.publication_id",
                readback.foreignKeys().get("fk_archive_readback_publication"));
        assertTrue(readback.checks().get("chk_archive_readback_state").contains("PENDING"));
        assertTrue(readback.checks().get("chk_archive_readback_state").contains("PASSED"));
        assertTrue(readback.checks().get("chk_archive_readback_state").contains("FAILED"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("bigint", false, null),
                expected.tables().get("archive_maintenance_job").columns()
                        .get("manager_authorization_revision"));
        var job = expected.tables().get("archive_maintenance_job");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("varchar(100)", true,
                "utf8mb4_0900_bin"), job.columns().get("target_agent_id"));
        for (String nullable : java.util.List.of("run_id", "appointment_id",
                "appointment_revision", "agent_id", "binding_version", "permission_profile",
                "work_id", "canonical_key", "title", "source_id", "source_sha256",
                "source_summary", "rights_basis", "draft_id")) {
            assertTrue(job.columns().get(nullable).nullable(), nullable);
        }
        assertTrue(job.checks().get("chk_archive_job_state").contains("WAITING_INPUT"));
        String waitingShape = job.checks().get("chk_archive_job_waiting_shape");
        assertTrue(waitingShape.contains("WAITING_ASSIGNEE"));
        assertTrue(waitingShape.contains("(source_idisnull)or(work_idisnull)"), waitingShape);
        assertTrue(waitingShape.contains("((run_idisnull)and(draft_idisnull)and(appointment_idisnull))or"
                + "((run_idisnotnull)and(draft_idisnotnull)and(appointment_idisnotnull)"), waitingShape);
        var previousWaiting = ArchiveMaintenanceSchemaCatalog.previousWaitingShapeJobTable(expected);
        assertEquals(job.columns(), previousWaiting.columns());
        assertEquals(job.indexes(), previousWaiting.indexes());
        assertEquals(job.foreignKeys(), previousWaiting.foreignKeys());
        assertTrue(!previousWaiting.checks().get("chk_archive_job_waiting_shape")
                .contains("(source_idisnull)or(work_idisnull)"));
        assertTrue(!previousWaiting.checks().get("chk_archive_job_waiting_shape")
                .contains("((run_idisnull)and(draft_idisnull)and(appointment_idisnull))or"));
        var legacyJob = ArchiveMaintenanceSchemaCatalog.legacyWaitingJobTable(expected);
        assertTrue(!legacyJob.columns().containsKey("target_agent_id"));
        assertTrue(!legacyJob.columns().get("run_id").nullable());
        assertEquals(Map.of(
                "chk_archive_job_mode", "YES:" + ArchiveMaintenanceSchemaCatalog.normalizeCheck(
                        "publication_mode IN ('MANUAL','AUTO')"),
                "chk_archive_job_revision", "YES:" + ArchiveMaintenanceSchemaCatalog.normalizeCheck(
                        "revision >= 1")), legacyJob.checks());
        var confirmation = expected.tables().get("archive_confirmed_request");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("longtext", false,
                "utf8mb4_0900_bin"), confirmation.columns().get("request_json"));
        assertEquals("0:tenant_id,client_id,owner_jiacn,request_intent_id",
                confirmation.indexes().get("uk_archive_confirmation_intent"));
        assertEquals("collection_id>archive_collection.collection_id",
                confirmation.foreignKeys().get("fk_archive_confirmation_collection"));
        assertTrue(confirmation.checks().get("chk_archive_confirmation_binding")
                .contains("canonical_message_id"));
        var run = expected.tables().get("archive_job_run");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("varchar(100)", true, "ascii_bin"),
                run.columns().get("started_message_id"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("bigint", true, null),
                run.columns().get("failure_retryable"));
        assertTrue(run.checks().get("chk_archive_run_state").contains("RUNNING"));
        assertTrue(run.checks().get("chk_archive_run_lifecycle").contains("started_message_id"));
        var execution = expected.tables().get("archive_execution_grant");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("bigint", false, null),
                execution.columns().get("manager_authorization_revision"));
        assertTrue(execution.checks().get("chk_archive_execution_state").contains("READ_ONLY"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("binary(32)", false, null),
                execution.columns().get("registration_hash"));
        assertEquals("0:tenant_id,client_id,owner_jiacn,dispatch_key",
                execution.indexes().get("uk_archive_execution_dispatch"));
        assertEquals("run_id>archive_job_run.run_id",
                execution.foreignKeys().get("fk_archive_execution_run"));
        for (String table : expected.tables().keySet()) {
            ArchiveMaintenanceSchemaCatalog.verify(table, expected.tables().get(table),
                    expected.tables().get(table), "InnoDB:utf8mb4_0900_bin");
        }
    }

    @Test
    void additiveDdlCanBeReplayedAfterAnyCommittedTable() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/archive-maintenance-schema.sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertEquals(ArchiveMaintenanceSchemaCatalog.TABLE_ORDER.size(),
                sql.split("CREATE TABLE IF NOT EXISTS ", -1).length - 1);
        for (String table : ArchiveMaintenanceSchemaCatalog.TABLE_ORDER) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table + " ("), table);
            assertTrue(!sql.contains("DROP TABLE " + table), table);
        }
        assertTrue(sql.contains("INSERT IGNORE INTO archive_collection"));
    }
    @Test
    void failsClosedForExistingColumnTypeIndexAndCheckDrift() {
        var draft = ArchiveMaintenanceSchemaCatalog.expected().tables().get("archive_draft");
        var columns = new LinkedHashMap<>(draft.columns());
        columns.put("content_json", new ArchiveMaintenanceSchemaCatalog.Column("text", false, "utf8mb4_0900_bin"));
        assertDrift(draft, new ArchiveMaintenanceSchemaCatalog.Table(columns, draft.indexes(),
                draft.foreignKeys(), draft.checks()), "columns");
        var indexes = new LinkedHashMap<>(draft.indexes());
        indexes.remove("uk_archive_draft_job");
        assertDrift(draft, new ArchiveMaintenanceSchemaCatalog.Table(draft.columns(), indexes,
                draft.foreignKeys(), draft.checks()), "indexes");
        var checks = new LinkedHashMap<>(draft.checks());
        checks.replace("chk_archive_draft_revision", "NO:" + checks.get("chk_archive_draft_revision").substring(4));
        assertDrift(draft, new ArchiveMaintenanceSchemaCatalog.Table(draft.columns(), draft.indexes(),
                draft.foreignKeys(), checks), "checks");
    }

    private static void assertDrift(ArchiveMaintenanceSchemaCatalog.Table expected,
                                    ArchiveMaintenanceSchemaCatalog.Table actual, String field) {
        var error = assertThrows(IllegalStateException.class, () -> ArchiveMaintenanceSchemaCatalog.verify(
                "archive_draft", expected, actual, "InnoDB:utf8mb4_0900_bin"));
        assertTrue(error.getMessage().endsWith("." + field), error.getMessage());
    }
}
