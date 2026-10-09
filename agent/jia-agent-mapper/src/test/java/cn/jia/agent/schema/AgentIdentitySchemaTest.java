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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(registry.contains("tenant_id = '0'"));
        assertTrue(registry.contains("client_id is null and owner_jiacn is null and tenant_id = '0'"));
        assertFalse(registry.contains("and tenant_id is null"));
        assertTrue(registry.contains("trim(client_id) <> ''"));
        assertTrue(registry.contains("trim(owner_jiacn) <> ''"));
        assertTrue(registry.contains("utf8mb4_0900_bin"));

        assertTrue(alias.contains("chk_identity_alias_no_blank_scope"));
        assertTrue(alias.contains("tenant_id = '0'"));
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

        // Statement extraction must preserve quoted SQL and comments, not truncate a CHECK oracle.
        String prefix = "create table if not exists statement_probe (";
        for (String body : List.of(
                "value varchar(100) comment 'before; after', id bigint)",
                "value varchar(100) comment 'doubled ''; still quoted', id bigint)",
                "value varchar(100) comment 'escaped \\'; still quoted', id bigint)",
                "value varchar(100) comment \"double; quote\", id bigint)",
                "value varchar(100) comment \"escaped \\\"; still quoted\", id bigint)",
                "value varchar(100) comment \"doubled \"\"; still quoted\", id bigint)",
                "`semi;colon``name` bigint, id bigint)",
                "id bigint /* block; ' quote */ , value bigint)",
                "id bigint -- line; ' quote\n, value bigint)",
                "id bigint # line; \" quote\r\n, value bigint)",
                "id bigint comment '-- # /* ; */', value bigint)")) {
            String statement = prefix + body;
            assertEquals(statement, tableDefinition(statement + "; select 1;", "statement_probe"));
        }
        for (String unfinished : List.of(
                "value varchar(100) comment 'unterminated;", "`unterminated;",
                "id bigint /* unterminated;", "id bigint) -- no terminator;")) {
            assertThrows(AssertionError.class,
                    () -> tableDefinition(prefix + unfinished, "statement_probe"));
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
        assertFalse(migration.contains("tenant_id = trim(owner_jiacn)"));
        assertFalse(migration.contains("tenant_id is null or tenant_id = owner_jiacn"));
        assertTrue(migration.contains("chk_agent_binding_single_tenant check (tenant_id = '0')"));
        assertTrue(migration.contains("drop check chk_agent_binding_tenant_owner"));
        assertTrue(migration.contains("drop check chk_agent_binding_single_tenant"));
        assertFalse(migration.contains("add constraint chk_agent_binding_tenant_owner"));
        String binding = compact(tableDefinition(readResource("db/schema.sql"), "agent_persona_binding"));
        assertTrue(binding.contains("tenant_id varchar(50) not null default '0'"));
        assertTrue(binding.contains("chk_agent_binding_single_tenant check (tenant_id = '0')"));
        assertTrue(binding.contains("unique key uk_agent_binding_active_persona "
                + "(tenant_id, client_id, active_persona_code)"));
        assertTrue(migration.contains("on agent_persona_binding (tenant_id, client_id, active_persona_code)"));
        assertTrue(migration.contains("registry tenant conversion requires separate approved maintenance"));
        assertTrue(migration.indexOf("registry tenant conversion requires separate approved maintenance")
                < migration.indexOf("alter table agent_persona_binding\n    modify column"));
        assertTrue(migration.contains("alter table agent_runtime add column owner_jiacn"));
        assertTrue(migration.contains("alter table agent_runtime add column persona_code"));
        assertTrue(migration.contains("alter table agent_runtime add column binding_id"));

        String complete = compact(migration);
        assertEquals(2, occurrenceCount(complete,
                "and client_id is null and owner_jiacn is null and tenant_id = '0'"));
        assertEquals(2, occurrenceCount(complete,
                "and owner_jiacn is not null and trim(owner_jiacn) <> '' and tenant_id = '0'"));
        assertEquals(2, occurrenceCount(complete, "chk_identity_alias_scope check (tenant_id = '0')"));
        assertEquals(2, occurrenceCount(complete,
                "comment 'single tenant scope; 0 for every identity including system rows'"));
        assertEquals(2, occurrenceCount(complete,
                "comment 'single tenant scope; always 0 | immutable after insert'"));
        assertTrue(complete.contains("modify column tenant_id varchar(50) not null default '0'"));
        assertEquals(3, occurrenceCount(complete,
                "where not (binary tenant_id <=> binary '0') or octet_length(tenant_id) <> 1"));
        assertTrue(complete.contains("where status = 1 group by client_id, persona_code having count(*) > 1"));
        for (String rejected : List.of("binding tenant conversion", "registry tenant conversion",
                "alias tenant conversion", "active persona conflict")) {
            int guard = migration.indexOf(rejected + " requires separate approved maintenance");
            assertTrue(guard >= 0, rejected);
            // All guards precede even the first runtime projection ALTER, not just a late CHECK.
            assertTrue(guard < migration.indexOf("alter table agent_runtime add column"), rejected);
            assertTrue(guard < migration.indexOf("alter table agent_persona_binding\n    modify column"), rejected);
            assertTrue(guard < migration.indexOf("drop foreign key fk_identity_alias_registry_scope"), rejected);
        }
        assertTrue(complete.contains("on update restrict on delete restrict"));
        assertFalse(complete.contains("on update cascade"));
        assertFalse(complete.contains("on delete cascade"));
        int targetIndex = migration.indexOf("create unique index uk_agent_binding_active_persona_a02");
        assertTrue(targetIndex >= 0);
        assertTrue(targetIndex < migration.indexOf("drop index uk_agent_binding_active_persona"));
        assertFalse(complete.contains("client_id,owner_jiacn,active_persona_code"));
        assertFalse(complete.contains("(client_id, owner_jiacn, active_persona_code)"));

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
                + "(tenant_id, client_id, active_persona_code)"));
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
        int start = sql.indexOf("create table if not exists " + table + " (");
        assertTrue(start >= 0, table);
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = start; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (quote != '`' && current == '\\' && next != 0) {
                    i++; // MySQL string backslash escape: the next character cannot close the quote.
                } else if (current == quote) {
                    if (next == quote) {
                        i++; // Doubled string/identifier quote remains inside the quoted value.
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '#' || (current == '-' && next == '-'
                    && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2))))) {
                lineComment = true;
            } else if (current == ';') {
                return sql.substring(start, i);
            }
        }
        throw new AssertionError("Missing unquoted SQL statement terminator for " + table);
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
