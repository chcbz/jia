package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityLegacyApplySchemaTest {

    @Test
    void manifestIsImmutableExplicitAndCarriesFullSnapshotApprovalEvidence() throws IOException {
        String schema = read("db/agent-identity-legacy-manifest-schema.sql").toLowerCase(Locale.ROOT);

        assertTrue(schema.contains("create table if not exists agent_identity_legacy_manifest"));
        assertTrue(schema.contains("source_row_sha256"));
        assertTrue(schema.contains("source_snapshot_row_count"));
        assertTrue(schema.contains("source_snapshot_sha256"));
        assertTrue(schema.contains("approved_report_sha256"));
        assertTrue(schema.contains("approved_by"));
        assertTrue(schema.contains("approved_at"));
        assertTrue(schema.contains("approval_status = 'approved'"));
        assertTrue(schema.contains("a08_manifest_assert"));
        assertTrue(schema.contains("incompatible manifest binding unique index"));
        assertTrue(schema.contains("incompatible manifest evidence columns"));
        assertTrue(schema.contains("trg_a08_manifest_no_update"));
        assertTrue(schema.contains("trg_a08_manifest_no_delete"));
        assertTrue(schema.contains("agent_identity_legacy_apply_run"));
        assertTrue(schema.contains("'running', 'succeeded', 'failed'"));
        assertFalse(schema.contains("insert into agent_identity_registry"));
        assertFalse(schema.contains("auto_eligible") && schema.contains("select * from"));
    }

    @Test
    void applyBindsApprovalToCurrentCompleteSnapshotAndAuditsSuccessFailureAndNoop() throws IOException {
        String apply = read("db/agent-identity-legacy-apply.sql").toLowerCase(Locale.ROOT);

        assertTrue(apply.contains("get_lock('cyf:a08:legacy-identity-apply'"));
        assertTrue(apply.contains("a08_current_binding_snapshot"));
        assertTrue(apply.contains("from agent_persona_binding b"));
        assertTrue(apply.contains("v_current_snapshot_count <> v_expected_snapshot_count"));
        assertTrue(apply.contains("v_current_snapshot_hash <> binary v_expected_snapshot_hash"));
        assertTrue(apply.contains("binary s.row_sha256 <> binary m.source_row_sha256"));
        assertTrue(apply.contains("binary approved_report_sha256 <> binary @a08_approved_report_sha256"));
        assertTrue(apply.contains("operator regexp '[[:cntrl:]]'"));
        assertTrue(apply.contains("run_status = 'failed'"));
        assertTrue(apply.contains("run_status = 'succeeded'"));
        assertTrue(apply.contains("not exists (\n           select 1 from agent_identity_registry"));
        assertTrue(apply.contains("not exists (\n           select 1 from agent_identity_alias"));
        assertTrue(apply.contains("registry canonical/binding conflict"));
        assertTrue(apply.contains("scoped active alias conflict"));
        assertTrue(apply.contains("source_agent_id <> binary m.canonical_agent_id"));
        assertFalse(apply.contains("resolution_status"));
        assertFalse(apply.contains("from auto_eligible"));
        assertEquals(1, occurrences(apply, "call a08_apply_legacy_identity_manifest()"));
    }

    @Test
    void snapshotHelperIsReadOnlyAndHashFormulaMatchesApply() throws IOException {
        String snapshot = read("db/agent-identity-legacy-snapshot.sql").toLowerCase(Locale.ROOT);
        String apply = read("db/agent-identity-legacy-apply.sql").toLowerCase(Locale.ROOT);

        assertTrue(snapshot.contains("hex(b.client_id)"));
        assertTrue(snapshot.contains("hex(b.agent_id)"));
        assertTrue(snapshot.contains("source_row_sha256"));
        assertTrue(snapshot.contains("source_snapshot_sha256"));
        assertTrue(snapshot.contains("lpad(binding_id, 20, '0')"));
        assertTrue(apply.contains("lpad(binding_id, 20, '0')"));
        assertFalse(snapshot.contains("insert into agent_identity_registry"));
        assertFalse(snapshot.contains("insert into agent_identity_alias"));
        assertFalse(snapshot.contains("auto_eligible"));
    }

    @Test
    void manifestTemplateRequiresManualBindingSelectionAndDocumentsLujunyiRisk() throws IOException {
        String template = read("db/agent-identity-legacy-manifest-template.sql").toLowerCase(Locale.ROOT);
        String schema = read("db/agent-identity-legacy-manifest-schema.sql").toLowerCase(Locale.ROOT);

        assertTrue(template.contains("does not select from"));
        assertTrue(template.contains("binding 5"));
        assertTrue(template.contains("manual selection"));
        assertTrue(schema.contains("lujunyi"));
        assertTrue(schema.contains("binding 5"));
    }

    private String read(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
