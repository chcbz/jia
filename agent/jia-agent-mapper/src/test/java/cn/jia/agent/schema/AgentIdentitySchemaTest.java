package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentitySchemaTest {

    @Test
    void schemaClosesCanonicalTypesLifecycleOwnerScopeAndRuntimeProjection() throws IOException {
        String schema = readResource("db/schema.sql");
        String registry = compact(tableDefinition(schema, "agent_identity_registry"));
        String alias = compact(tableDefinition(schema, "agent_identity_alias"));
        String runtime = compact(tableDefinition(schema, "agent_runtime"));

        assertTrue(registry.contains("unique key uk_identity_registry_agent (canonical_agent_id)"));
        assertTrue(registry.contains("canonical_type in ('opaque', 'legacy_canonical', 'system')"));
        assertTrue(registry.contains(
                "lifecycle_status in ('provisioned', 'active', 'suspended', 'retired')"));
        assertTrue(registry.contains("canonical_agent_id regexp '^agt_[0-9a-f]{32}$'"));
        assertTrue(registry.contains("tenant_id = trim(owner_jiacn)"));
        assertTrue(registry.contains("trim(client_id) <> ''"));
        assertTrue(registry.contains("trim(owner_jiacn) <> ''"));
        assertTrue(registry.contains("utf8mb4_0900_bin"));

        assertTrue(alias.contains("chk_identity_alias_no_blank_scope"));
        assertTrue(alias.contains("tenant_id = trim(owner_jiacn)"));
        assertTrue(alias.contains("foreign key "
                + "(registry_id, canonical_agent_id, client_id, owner_jiacn, tenant_id)"));
        assertTrue(alias.contains("unique key uk_identity_alias_active "
                + "(client_id, owner_jiacn, alias_type, alias_value, active_key)"));
        assertTrue(alias.contains("utf8mb4_0900_bin"));

        assertTrue(runtime.contains("owner_jiacn varchar(50) default null"));
        assertTrue(runtime.contains("persona_code varchar(50) default null"));
        assertTrue(runtime.contains("binding_id bigint default null"));
        assertTrue(runtime.contains("key idx_agent_runtime_owner (client_id, owner_jiacn)"));
        assertTrue(runtime.contains("key idx_agent_runtime_persona_code (persona_code)"));
    }

    @Test
    void schemaAndMigrationCreateDefinitionsStayInParity() throws IOException {
        String schema = readResource("db/schema.sql");
        String migration = readResource("db/agent-identity-schema.sql");
        for (String table : List.of("agent_identity_registry", "agent_identity_alias")) {
            assertEquals(compact(tableDefinition(schema, table)),
                    compact(tableDefinition(migration, table)), table);
        }
    }

    @Test
    void migrationContainsRealB0UpgradeAndMysql8021RepeatableTriggers() throws IOException {
        String migration = readResource("db/agent-identity-schema.sql");
        String executable = withoutLineComments(migration);

        assertTrue(migration.contains("convert to character set utf8mb4 collate utf8mb4_0900_bin"));
        assertTrue(migration.contains("alter table agent_identity_registry\n    modify column"));
        assertTrue(migration.contains("alter table agent_identity_alias\n    modify column"));
        assertTrue(migration.contains("drop foreign key fk_identity_alias_registry_scope"));
        assertTrue(migration.contains("add constraint fk_identity_alias_registry_scope foreign key"));
        assertTrue(migration.contains("drop check"));
        assertTrue(migration.contains("chk_identity_alias_no_blank_scope"));
        assertTrue(migration.contains("alter table agent_runtime add column owner_jiacn"));
        assertTrue(migration.contains("alter table agent_runtime add column persona_code"));
        assertTrue(migration.contains("alter table agent_runtime add column binding_id"));

        assertFalse(executable.contains("create trigger if not exists"));
        for (String trigger : List.of(
                "trg_identity_registry_immutable_update", "trg_identity_registry_no_delete",
                "trg_identity_alias_immutable_update", "trg_identity_alias_no_delete")) {
            assertEquals(1, occurrenceCount(migration, "drop trigger if exists " + trigger));
            assertEquals(1, occurrenceCount(migration, "create trigger " + trigger));
        }
        assertTrue(migration.contains("retired identity cannot be resurrected"));
        assertTrue(migration.contains("physical delete of identity registry is forbidden"));
        assertFalse(Pattern.compile("(?m)^\\s*(insert|update|delete|replace|truncate)\\b")
                .matcher(executable).find());
    }

    @Test
    void dryRunIsExecutableSingleCandidatePipelineAndReadOnly() throws IOException {
        String dryRun = readResource("db/agent-identity-dry-run.sql");
        String executable = withoutLineComments(dryRun);

        assertFalse(Pattern.compile("(?m)^\\s*(insert|update|delete|alter|create|drop|truncate|replace)\\b")
                .matcher(executable).find());
        assertEquals(1, cteDefinitionCount(dryRun, "linked_runtime_candidates"));
        assertEquals(1, cteDefinitionCount(dryRun, "exact_runtime_candidates"));
        assertEquals(1, cteDefinitionCount(dryRun, "raw_candidate_evidence"));
        assertEquals(1, cteDefinitionCount(dryRun, "resolved_candidate"));
        assertEquals(0, cteDefinitionCount(dryRun, "linked_candidate_counts"));
        assertEquals(0, cteDefinitionCount(dryRun, "exact_candidate_counts"));
        assertTrue(dryRun.indexOf("linked_runtime_candidates as")
                < dryRun.indexOf("resolved_candidate as"));
        assertTrue(dryRun.indexOf("exact_runtime_candidates as")
                < dryRun.indexOf("resolved_candidate as"));
        assertTrue(dryRun.contains("linked_exact_candidate_conflict"));
        assertTrue(dryRun.contains("blocked_linked_exact_conflict"));
        assertTrue(dryRun.contains("blocked_task_scope_missing"));
        assertTrue(dryRun.contains("task_missing_scope_count"));

        for (String column : List.of(
                "canonical_agent_id", "canonical_type", "lifecycle_status", "legacy_agent_id",
                "client_id", "owner_jiacn", "tenant_id", "persona_code_evidence",
                "profile_id_evidence", "runtime_match", "binding_match", "task_reference_count",
                "resolution_status", "resolution_reason")) {
            assertTrue(dryRun.contains(column), column);
        }
    }

    @Test
    void bindingGeneratedColumnsAndScopedIndexesRemainFrozen() throws IOException {
        String binding = compact(tableDefinition(readResource("db/schema.sql"),
                "agent_persona_binding"));
        assertTrue(binding.contains("owner_jiacn varchar(50) generated always as (jiacn) stored"));
        assertTrue(binding.contains("when 2 then 'provisioned'"));
        assertTrue(binding.contains("when 3 then 'retired'"));
        assertTrue(binding.contains("unique key uk_agent_binding_active_persona "
                + "(client_id, owner_jiacn, active_persona_code)"));
        assertTrue(binding.contains("unique key uk_agent_binding_active_agent (active_agent_id)"));
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

    private int cteDefinitionCount(String sql, String cte) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(cte) + "\\s+as\\s*\\(")
                .matcher(sql);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int occurrenceCount(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String withoutLineComments(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", "");
    }
}
