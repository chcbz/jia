package cn.jia.chat.archive.maintenance.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        var checkpoint = expected.tables().get("archive_draft_block_checkpoint");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("char(64)", false, "ascii_bin"),
                checkpoint.columns().get("block_sha256"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("varchar(255)", false, "ascii_bin"),
                checkpoint.columns().get("storage_uri"));
        assertEquals("0:draft_id,draft_revision,block_index",
                checkpoint.indexes().get("PRIMARY"));
        assertEquals("draft_id>archive_draft.draft_id",
                checkpoint.foreignKeys().get("fk_archive_checkpoint_draft"));
        assertTrue(checkpoint.checks().get("chk_archive_checkpoint_actor")
                .contains("RUNTIME"));
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
        var failure = expected.tables().get("archive_execution_failure");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("bigint", false, null),
                failure.columns().get("failure_id"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("char(64)", false, "ascii_bin"),
                failure.columns().get("input_fingerprint"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("char(64)", false, "ascii_bin"),
                failure.columns().get("registration_fingerprint"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("varchar(16)", false, "ascii_bin"),
                failure.columns().get("source_verification_state"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("char(64)", true, "ascii_bin"),
                failure.columns().get("repair_evidence_fingerprint"));
        assertEquals("0:run_id", failure.indexes().get("uk_archive_failure_run"));
        assertEquals("1:job_id,failure_id", failure.indexes().get("idx_archive_failure_job_history"));
        assertEquals("run_id>archive_job_run.run_id",
                failure.foreignKeys().get("fk_archive_failure_run"));
        assertTrue(failure.checks().get("chk_archive_failure_resolution")
                .contains("RUNTIME_REPAIRED"));
        assertTrue(failure.checks().get("chk_archive_failure_source_verification")
                .contains("READABLE"));
        var sourceArtifact = expected.tables().get("archive_source_artifact_object");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("varchar(255)", false, "ascii_bin"),
                sourceArtifact.columns().get("storage_uri"));
        assertEquals("1:state,touched_at,source_id", sourceArtifact.indexes().get("idx_archive_source_artifact_cleanup"));
        assertEquals("1:tenant_id,client_id,owner_jiacn,operation_key",
                sourceArtifact.indexes().get("idx_archive_source_artifact_operation"));
        assertFalse(sourceArtifact.indexes().containsKey("uk_archive_source_artifact_operation"));
        assertEquals("tenant_id>archive_operation.tenant_id,client_id>archive_operation.client_id,owner_jiacn>archive_operation.owner_jiacn,operation_key>archive_operation.operation_key",
                sourceArtifact.foreignKeys().get("fk_archive_source_artifact_operation"));
        assertTrue(sourceArtifact.checks().get("chk_archive_source_artifact_state").contains("DELETE_PENDING"));
        var execution = expected.tables().get("archive_execution_grant");
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("bigint", false, null),
                execution.columns().get("manager_authorization_revision"));
        assertTrue(execution.checks().get("chk_archive_execution_state").contains("READ_ONLY"));
        assertEquals(new ArchiveMaintenanceSchemaCatalog.Column("binary(32)", false, null),
                execution.columns().get("registration_hash"));
        assertEquals("0:tenant_id,client_id,owner_jiacn,dispatch_key",
                execution.indexes().get("uk_archive_execution_dispatch"));
        assertEquals("1:tenant_id,client_id,owner_jiacn,agent_id,installation_ref,state,grant_ref",
                execution.indexes().get("idx_archive_execution_installation"));
        assertEquals("run_id>archive_job_run.run_id",
                execution.foreignKeys().get("fk_archive_execution_run"));
        for (String table : expected.tables().keySet()) {
            ArchiveMaintenanceSchemaCatalog.verify(table, expected.tables().get(table),
                    expected.tables().get(table), "InnoDB:utf8mb4_0900_bin");
        }
    }

    @Test
    void reclaimIndexPredecessorDiffersOnlyByTheExactIndexAndDriftIsRejected() {
        var expected = ArchiveMaintenanceSchemaCatalog.expected();
        var current = expected.tables().get("archive_execution_grant");
        var previous = ArchiveMaintenanceSchemaCatalog.predecessorExecutionGrantTable(expected);
        assertEquals(current.columns(), previous.columns());
        assertEquals(current.foreignKeys(), previous.foreignKeys());
        assertEquals(current.checks(), previous.checks());
        assertEquals(current.indexes().size() - 1, previous.indexes().size());
        assertTrue(!previous.indexes().containsKey("idx_archive_execution_installation"));
        var missing = assertThrows(IllegalStateException.class, () ->
                ArchiveMaintenanceSchemaCatalog.verify("archive_execution_grant", current, previous,
                        "InnoDB:utf8mb4_0900_bin"));
        assertTrue(missing.getMessage().endsWith(".indexes"));
        var wrongIndexes = new LinkedHashMap<>(current.indexes());
        wrongIndexes.put("idx_archive_execution_installation", "1:installation_ref,state,grant_ref");
        var wrong = new ArchiveMaintenanceSchemaCatalog.Table(current.columns(), wrongIndexes,
                current.foreignKeys(), current.checks());
        var drift = assertThrows(IllegalStateException.class, () ->
                ArchiveMaintenanceSchemaCatalog.verify("archive_execution_grant", current, wrong,
                        "InnoDB:utf8mb4_0900_bin"));
        assertTrue(drift.getMessage().endsWith(".indexes"));
    }

    @Test
    void outboxAndReadbackPredecessorsAreExactGitBytes() throws Exception {
        assertHistoricalPredecessor("18d66419", 32034, 18,
                "5f358f3cd71f5cd8c7ecc0c633ae3aa0770305e913fc1f0d711c5245847213c2");
        assertHistoricalPredecessor("eb31260f", 30916, 17,
                "c398de55270b8a7dd0467e796ae7a8dd0444458c51f0b437e7ac90f11a9c4118");
    }

    private void assertHistoricalPredecessor(String revision, int byteLength, int tableCount,
            String sha256) throws Exception {
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/archive-maintenance-schema-" + revision + ".sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertEquals(byteLength, bytes.length);
        assertEquals(sha256, cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes));
        String sql = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(sql.contains("\r"), "Immutable predecessor must retain exact LF Git bytes");
        assertEquals(tableCount, sql.split("CREATE TABLE IF NOT EXISTS ", -1).length - 1);
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS archive_business_outbox ("));
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS archive_source_artifact_object ("));
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS archive_execution_failure ("));
        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS archive_draft_block_checkpoint ("));
        assertEquals("18d66419".equals(revision),
                sql.contains("CREATE TABLE IF NOT EXISTS archive_publication_readback ("));
        // The real MySQL selectors initialize these exact bytes and verify the entire
        // supported catalog. parse() intentionally accepts only the full current table set.
    }

    @Test
    void checkpointPredecessorFixtureIsExact0d2a6e6GitBlob() throws Exception {
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/archive-maintenance-schema-0d2a6e6.sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertEquals(38408, bytes.length);
        assertEquals("b17881687dd423d15fd3f73156a991f4c176ae4c0b71f87b8d515b69790832ce",
                cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes));
        String sql = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(20, sql.split("CREATE TABLE IF NOT EXISTS ", -1).length - 1);
        assertTrue(!sql.contains("archive_draft_block_checkpoint"));
    }

    @Test
    void platformReclaimPredecessorFixtureIsExactF7811daGitBlob() throws Exception {
        byte[] bytes;
        try(var input=getClass().getClassLoader().getResourceAsStream("db/agent-platform-skills-schema-f7811da.sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);bytes=input.readAllBytes();
        }
        assertEquals(2775,bytes.length);
        assertEquals("c86b77b0047b772cc8d1b49cf773c6ce9568ab4dc69ba67e40b33938e9542e42",
                cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes));
        String sql=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(2,sql.split("CREATE TABLE IF NOT EXISTS ",-1).length-1);
        assertFalse(sql.contains("reclaim_batch_json"));assertFalse(sql.contains("RECLAIMABLE"));
    }

    @Test
    void sourceLifecyclePredecessorFixtureIsExactF7811daGitBlob() throws Exception {
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/archive-maintenance-schema-f7811da.sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertEquals(40311, bytes.length);
        assertEquals("f814993101130aa9185c89ccba1ba134378a2c1139c098df8fd70782f54e3e6b",
                cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes));
        String sql = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(21, sql.split("CREATE TABLE IF NOT EXISTS ", -1).length - 1);
        assertTrue(!sql.contains("archive_source_artifact_object"));
        assertTrue(sql.contains("archive_draft_block_checkpoint"));
        assertTrue(!sql.contains("idx_archive_execution_installation"));
    }

    @Test
    void recovery13PredecessorFixtureIsExact659b66cGitBlob() throws Exception {
        byte[] bytes;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/archive-maintenance-schema-659b66c.sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            bytes = input.readAllBytes();
        }
        assertEquals(34715, bytes.length);
        assertEquals("782a64f159bdd36df0657ad29c11bff4c7e122ad0dac56fc7f8fe30eab7408b2",
                cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes));
        String sql = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(19, sql.split("CREATE TABLE IF NOT EXISTS ", -1).length - 1);
        assertTrue(!sql.contains("archive_execution_failure"));
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
    void sourceArtifactShaCheckMatchesCanonicalMySqlMetadataExactly() {
        var expected = ArchiveMaintenanceSchemaCatalog.expected().tables().get("archive_source_artifact_object");
        assertEquals("YES:regexp_like(sha256,'^[0-9a-f]{64}$')",
                expected.checks().get("chk_archive_source_artifact_sha"));
        for (String metadata : java.util.List.of(
                "regexp_like(`sha256`,_utf8mb4'^[0-9a-f]{64}$')",
                "regexp_like(`sha256`,_utf8mb4\\'^[0-9a-f]{64}$\\')")) {
            var actualChecks = new LinkedHashMap<>(expected.checks());
            actualChecks.put("chk_archive_source_artifact_sha",
                    "YES:" + ArchiveMaintenanceSchemaCatalog.normalizeCheck(metadata));
            assertEquals(expected.checks(), actualChecks);
            assertDoesNotThrow(() -> ArchiveMaintenanceSchemaCatalog.verify("archive_source_artifact_object",
                    expected, new ArchiveMaintenanceSchemaCatalog.Table(expected.columns(), expected.indexes(),
                            expected.foreignKeys(), actualChecks), "InnoDB:utf8mb4_0900_bin"));
        }
    }

    @Test
    void sourceArtifactAlteredRegexPatternIsStillSchemaDrift() {
        var expected = ArchiveMaintenanceSchemaCatalog.expected().tables().get("archive_source_artifact_object");
        var actualChecks = new LinkedHashMap<>(expected.checks());
        actualChecks.put("chk_archive_source_artifact_sha", "YES:" + ArchiveMaintenanceSchemaCatalog.normalizeCheck(
                "regexp_like(`sha256`,_utf8mb4'^[0-9a-f]{63}$')"));
        var failure = assertThrows(IllegalStateException.class, () -> ArchiveMaintenanceSchemaCatalog.verify(
                "archive_source_artifact_object", expected,
                new ArchiveMaintenanceSchemaCatalog.Table(expected.columns(), expected.indexes(),
                        expected.foreignKeys(), actualChecks), "InnoDB:utf8mb4_0900_bin"));
        assertEquals("Archive maintenance schema drift at archive_source_artifact_object.checks", failure.getMessage());
    }

    @Test
    void sourceArtifactExtraColumnIsStillSchemaDrift() {
        var expected = ArchiveMaintenanceSchemaCatalog.expected().tables().get("archive_source_artifact_object");
        var columns = new LinkedHashMap<>(expected.columns());
        columns.put("unsupported_drift", new ArchiveMaintenanceSchemaCatalog.Column("bigint", true, null));
        var failure = assertThrows(IllegalStateException.class, () -> ArchiveMaintenanceSchemaCatalog.verify(
                "archive_source_artifact_object", expected,
                new ArchiveMaintenanceSchemaCatalog.Table(columns, expected.indexes(),
                        expected.foreignKeys(), expected.checks()), "InnoDB:utf8mb4_0900_bin"));
        assertEquals("Archive maintenance schema drift at archive_source_artifact_object.columns", failure.getMessage());
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
