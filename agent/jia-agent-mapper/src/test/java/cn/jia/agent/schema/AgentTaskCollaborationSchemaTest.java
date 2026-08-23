package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskCollaborationSchemaTest {
    private static final List<String> COLLABORATION_TABLES = List.of(
            "agent_task_member",
            "agent_task_work_item",
            "agent_task_request",
            "agent_task_artifact");

    @Test
    void schemaAndMigrationDeclareTheSameScopedCollaborationTables() throws IOException {
        String schema = readResource("db/schema.sql");
        String migration = readResource("db/task-collaboration-schema.sql");

        for (String table : COLLABORATION_TABLES) {
            String schemaDefinition = tableDefinition(schema, table);
            String migrationDefinition = tableDefinition(migration, table);
            assertEquals(compact(schemaDefinition), compact(migrationDefinition), table);
            assertTrue(compact(schemaDefinition).contains("tenant_id varchar(50) not null"), table);
            assertTrue(compact(schemaDefinition).contains("client_id varchar(50) not null"), table);
        }
    }

    @Test
    void businessKeysAndLookupIndexesKeepRequiredScopeAndArtifactVersion() throws IOException {
        String schema = readResource("db/schema.sql");

        Map<String, String> requiredIndexes = Map.ofEntries(
                Map.entry("agent_task_member",
                        "unique key uk_task_member_scope (tenant_id, client_id, task_id, agent_id)"),
                Map.entry("agent_task_work_item",
                        "unique key uk_work_item_scope (tenant_id, client_id, work_item_id)"),
                Map.entry("agent_task_request",
                        "unique key uk_task_request_scope (tenant_id, client_id, request_id)"),
                Map.entry("agent_task_artifact",
                        "unique key uk_artifact_version (tenant_id, client_id, artifact_id, artifact_version)"));
        for (Map.Entry<String, String> entry : requiredIndexes.entrySet()) {
            assertTrue(compact(tableDefinition(schema, entry.getKey())).contains(entry.getValue()), entry.getKey());
        }

        String workItem = compact(tableDefinition(schema, "agent_task_work_item"));
        assertTrue(workItem.contains("required_item tinyint(1) not null default 1"));
        assertTrue(workItem.contains("submitted_at bigint default null"));
        assertTrue(workItem.contains("completed_at bigint default null"));

        String artifact = compact(tableDefinition(schema, "agent_task_artifact"));
        assertFalse(artifact.contains("unique key uk_artifact_scope (tenant_id, client_id, artifact_id)"));
        assertFalse(artifact.contains("supersedes_artifact_id"));
    }

    @Test
    void taskMetaExtensionIsSynchronizedWithoutTighteningLegacyScopeNullability() throws IOException {
        String schema = readResource("db/schema.sql");
        String migration = readResource("db/task-collaboration-schema.sql");
        String meta = compact(tableDefinition(schema, "agent_task_meta"));

        for (String column : List.of(
                "collaboration_mode", "risk_level", "max_agents", "coordinator_agent_id",
                "review_required", "task_version", "current_event_version")) {
            assertTrue(meta.contains(column), column);
            assertTrue(migration.contains("column " + column), column);
        }
        assertTrue(meta.contains("tenant_id varchar(50) default null"));
        assertTrue(meta.contains("client_id varchar(50) default null"));
        assertTrue(meta.contains("key idx_agent_task_meta_scope_status "
                + "(tenant_id, client_id, reward_status, update_time, id)"));
        assertTrue(meta.contains("key idx_agent_task_meta_scope_coordinator "
                + "(tenant_id, client_id, coordinator_agent_id, reward_status)"));
        assertTrue(meta.contains("unique key uk_agent_task_meta_scope "
                + "(tenant_id, client_id, task_id)"));
        assertFalse(meta.contains("unique key uk_agent_task_meta_task_id (task_id)"));
        assertTrue(migration.contains("create unique index uk_agent_task_meta_scope"));
        assertTrue(migration.contains("count(*) = 3"));
        assertTrue(migration.contains("group_concat(lower(column_name) order by seq_in_index separator ',')"));
        assertTrue(migration.contains("having count(*) = 1 and max(lower(column_name)) = 'task_id'"));
    }

    @Test
    void blanketTenantDefaultMigrationExcludesCollaborationOwnedTables() throws IOException {
        String migration = readResource("db/migration-tenant-default-zero.sql");
        String normalizedMigration = compact(migration);
        for (String table : List.of(
                "agent_task_meta", "agent_task_member", "agent_task_work_item",
                "agent_task_request", "agent_task_artifact", "agent_task_event",
                "agent_task_thread", "agent_task_backfill_issue",
                "agent_task_backfill_manifest_batch", "agent_task_backfill_manifest",
                "agent_task_backfill_run", "agent_task_historical_event_manifest",
                "agent_task_historical_event_batch", "agent_task_historical_event_run")) {
            assertFalse(migration.contains("`" + table + "`"), table);
        }
        assertFalse(normalizedMigration.contains(
                "update `chat_conversation` set tenant_id = '0'"));
        assertFalse(normalizedMigration.contains(
                "update `chat_message` set tenant_id = '0'"));
        assertTrue(normalizedMigration.contains(
                "alter table `chat_conversation` modify column tenant_id varchar(50) default '0'"));
        assertTrue(normalizedMigration.contains(
                "alter table `chat_message` modify column tenant_id varchar(50) default '0'"));
        assertTrue(normalizedMigration.contains(
                "any data normalization requires a separate audited migration"));
    }

    @Test
    void b01ResourcesDoNotCreateOutOfScopeCollaborationOrIdentityTables() throws IOException {
        String b01Sql = readResource("db/task-collaboration-schema.sql");
        for (String forbiddenTable : List.of(
                "agent_identity_registry", "agent_identity_alias", "agent_task_thread", "agent_task_event",
                "agent_outbox", "agent_inbox", "agent_delivery", "agent_task_outbox", "agent_task_inbox",
                "agent_task_delivery")) {
            assertFalse(b01Sql.contains("create table if not exists " + forbiddenTable), forbiddenTable);
        }
        for (String table : COLLABORATION_TABLES) {
            String definition = tableDefinition(b01Sql, table);
            assertFalse(definition.contains("persona_code"), table);
            assertFalse(definition.contains("runtime_instance_id"), table);
            assertFalse(definition.contains("websocket_session_id"), table);
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
}
