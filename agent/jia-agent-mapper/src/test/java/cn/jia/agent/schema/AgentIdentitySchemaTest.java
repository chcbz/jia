package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentitySchemaTest {

    @Test
    void schemaClosesCanonicalTypesLifecycleAndOwnerScope() throws IOException {
        String schema = readResource("db/schema.sql");
        String registry = compact(tableDefinition(schema, "agent_identity_registry"));

        assertTrue(registry.contains("unique key uk_identity_registry_agent (canonical_agent_id)"));
        assertTrue(registry.contains("canonical_type in ('opaque', 'legacy_canonical', 'system')"));
        assertTrue(registry.contains(
                "lifecycle_status in ('provisioned', 'active', 'suspended', 'retired')"));
        assertTrue(registry.contains("canonical_agent_id regexp '^agt_[0-9a-f]{32}$'"));
        assertTrue(registry.contains("canonical_agent_id = 'builtin-songjiang'"));
        assertTrue(registry.contains("tenant_id = trim(owner_jiacn)"));
        assertTrue(registry.contains(
                "(lifecycle_status = 'retired' and retired_at is not null) "
                        + "or (lifecycle_status <> 'retired' and retired_at is null)"));
        assertTrue(registry.contains("unique key uk_identity_registry_alias_target "
                + "(id, canonical_agent_id, client_id, owner_jiacn, tenant_id)"));
        assertTrue(registry.contains("trim(client_id) <> ''"));
        assertTrue(registry.contains("trim(owner_jiacn) <> ''"));
        assertTrue(registry.contains("utf8mb4_0900_bin"));
        assertTrue(registry.contains("immutable after insert"));
        assertTrue(registry.contains("retired is terminal"));
        assertTrue(registry.contains("never reused even after delete"));
    }

    @Test
    void schemaAndMigrationKeepIdentityTablesInParity() throws IOException {
        String schema = readResource("db/schema.sql");
        String migration = readResource("db/agent-identity-schema.sql");

        for (String table : List.of("agent_identity_registry", "agent_identity_alias")) {
            assertTrue(compact(tableDefinition(schema, table))
                    .equals(compact(tableDefinition(migration, table))), table);
        }
    }

    @Test
    void activeAliasAndPersonaUseGeneratedScopedKeys() throws IOException {
        String schema = readResource("db/schema.sql");
        String alias = compact(tableDefinition(schema, "agent_identity_alias"));
        String binding = compact(tableDefinition(schema, "agent_persona_binding"));

        assertTrue(alias.contains("alias_type = 'legacy_agent_id'"));
        assertTrue(alias.contains("foreign key "
                + "(registry_id, canonical_agent_id, client_id, owner_jiacn, tenant_id)"));
        assertTrue(alias.contains(
                "case when alias_status = 'active' and valid_to is null then 1 else null end"));
        assertTrue(alias.contains("unique key uk_identity_alias_active "
                + "(client_id, owner_jiacn, alias_type, alias_value, active_key)"));
        assertFalse(alias.contains("unique key uk_identity_alias_active "
                + "(client_id, owner_jiacn, alias_type, alias_value, valid_to)"));
        assertTrue(alias.contains("chk_identity_alias_no_blank_scope"));
        assertTrue(alias.contains("trim(client_id) <> '' and trim(owner_jiacn) <> ''"));
        assertTrue(alias.contains("utf8mb4_0900_bin"));
        assertTrue(alias.contains("once revoked cannot become active again"));

        assertTrue(binding.contains("owner_jiacn varchar(50) generated always as (jiacn) stored"));
        assertTrue(binding.contains("when 2 then 'provisioned'"));
        assertTrue(binding.contains("when 1 then 'active'"));
        assertTrue(binding.contains("when 0 then 'suspended'"));
        assertTrue(binding.contains("when 3 then 'retired'"));
        assertTrue(binding.contains("unique key uk_agent_binding_active_persona "
                + "(client_id, owner_jiacn, active_persona_code)"));
        assertTrue(binding.contains("unique key uk_agent_binding_active_agent (active_agent_id)"));
        assertTrue(binding.contains("tenant_id is null or tenant_id = owner_jiacn"));
    }

    @Test
    void migrationIsRepeatableDdlAndDoesNotRewriteHistoricalRows() throws IOException {
        String migration = readResource("db/agent-identity-schema.sql");

        assertTrue(migration.contains("create table if not exists agent_identity_registry"));
        assertTrue(migration.contains("create table if not exists agent_identity_alias"));
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("prepare a02_stmt"));
        assertTrue(migration.contains("uk_agent_binding_active_persona_a02"));
        assertTrue(migration.contains("rename index uk_agent_binding_active_persona_a02"));
        assertTrue(migration.contains("uk_agent_binding_active_agent_a02"));
        assertTrue(migration.contains("rename index uk_agent_binding_active_agent_a02"));
        assertTrue(migration.contains("client_id, owner_jiacn, active_persona_code"));
        assertFalse(Pattern.compile("(?m)^\\s*(insert|update|delete|replace|truncate)\\b")
                .matcher(withoutLineComments(migration)).find());
        assertTrue(migration.contains("trg_identity_registry_immutable_update"));
        assertTrue(migration.contains("trg_identity_registry_no_delete"));
        assertTrue(migration.contains("trg_identity_alias_immutable_update"));
        assertTrue(migration.contains("trg_identity_alias_no_delete"));
        assertTrue(migration.contains("retired identity cannot be resurrected"));
        assertTrue(migration.contains("physical deletion of agent_identity_registry is forbidden"));
        assertTrue(migration.contains("revoked alias cannot be reactivated"));
        assertTrue(migration.contains("trg_identity_registry_immutable_update"));
        assertTrue(migration.contains("trg_identity_registry_no_delete"));
        assertTrue(migration.contains("trg_identity_alias_immutable_update"));
        assertTrue(migration.contains("trg_identity_alias_no_delete"));
        assertTrue(migration.contains("retired identity cannot be resurrected"));
        assertTrue(migration.contains("physical deletion of agent_identity_registry is forbidden"));
        assertTrue(migration.contains("revoked alias cannot be reactivated"));

        for (String b01Table : List.of(
                "agent_task_member", "agent_task_work_item", "agent_task_request", "agent_task_artifact")) {
            assertFalse(migration.contains("create table if not exists " + b01Table), b01Table);
        }
    }

    @Test
    void dryRunIsReadOnlyAndEmitsRequiredAuditGateColumns() throws IOException {
        String dryRun = readResource("db/agent-identity-dry-run.sql");
        String executable = withoutLineComments(dryRun);

        assertFalse(Pattern.compile("(?m)^\\s*(insert|update|delete|alter|create|drop|truncate|replace)\\b")
                .matcher(executable).find());
        for (String column : List.of(
                "canonical_agent_id", "canonical_type", "lifecycle_status", "legacy_agent_id",
                "client_id", "owner_jiacn", "tenant_id", "persona_code_evidence",
                "profile_id_evidence", "runtime_match", "binding_match", "task_reference_count",
                "resolution_status", "resolution_reason")) {
            assertTrue(dryRun.contains(column), column);
        }
        for (String blocker : List.of(
                "blocked_missing_scope", "blocked_tenant_owner_mismatch", "blocked_cross_owner",
                "blocked_multiple_bindings", "blocked_runtime_conflict",
                "blocked_multiple_alias_targets", "blocked_task_scope_conflict", "blocked_no_binding",
                "report_only_system_reference", "auto_eligible")) {
            assertTrue(dryRun.contains(blocker), blocker);
        }
        assertTrue(dryRun.contains("alias_type is only legacy_agent_id"));
        assertTrue(dryRun.contains("cast(null as char(100)) as profile_id_evidence"));
        for (String newBlocker : List.of(
                "blocked_exact_multi_candidate", "blocked_linked_multi_candidate",
                "blocked_linked_conflicting_canonical", "blocked_task_scope_missing")) {
            assertTrue(dryRun.contains(newBlocker), newBlocker);
        }
        for (String newBlocker : List.of(
                "blocked_exact_multi_candidate", "blocked_linked_multi_candidate",
                "blocked_linked_conflicting_canonical", "blocked_task_scope_missing")) {
            assertTrue(dryRun.contains(newBlocker), newBlocker);
        }
    }

    private String readResource(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
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
