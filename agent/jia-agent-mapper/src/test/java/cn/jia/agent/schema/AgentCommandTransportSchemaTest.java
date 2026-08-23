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

/** D01 canonical/migration contract for reliable Agent command transport. */
class AgentCommandTransportSchemaTest {
    private static final List<String> TABLES = List.of(
            "agent_command_delivery", "agent_outbox_event", "agent_consumer_inbox");

    @Test
    void canonicalAndIndependentMigrationDeclareByteExactSameThreeTables() throws IOException {
        String schema = read("db/schema.sql");
        String migration = read("db/agent-command-transport-schema.sql");

        for (String table : TABLES) {
            assertEquals(compact(tableDefinition(schema, table)),
                    compact(tableDefinition(migration, table)), table);
        }
        assertEquals(3, occurrences(migration, "create table if not exists agent_"));
        for (String forbidden : List.of(
                "agent_command_outbox", "agent_command_inbox",
                "agent_task_outbox", "agent_task_inbox", "agent_task_delivery")) {
            assertFalse(schema.contains("create table if not exists " + forbidden), forbidden);
            assertFalse(migration.contains("create table if not exists " + forbidden), forbidden);
        }
    }

    @Test
    void scopedBusinessAndTransportIdempotencyKeysAreExact() throws IOException {
        String schema = read("db/schema.sql");
        String delivery = compact(tableDefinition(schema, "agent_command_delivery"));
        String outbox = compact(tableDefinition(schema, "agent_outbox_event"));
        String inbox = compact(tableDefinition(schema, "agent_consumer_inbox"));

        assertTrue(delivery.contains(
                "unique key uk_delivery_command (tenant_id, client_id, command_id)"));
        assertTrue(outbox.contains(
                "unique key uk_outbox_event_id (tenant_id, client_id, event_id)"));
        assertTrue(inbox.contains(
                "unique key uk_consumer_message (tenant_id, client_id, consumer_name, message_id)"));

        assertFalse(outbox.contains("unique key uk_outbox_message"));
        assertFalse(Pattern.compile("unique key [^(]+\\([^)]*message_id")
                .matcher(outbox).find(), "outbox message_id must never be unique");
        assertTrue(outbox.contains("key idx_outbox_message (tenant_id, client_id, message_id)"));
    }

    @Test
    void sensitivePayloadAllowlistSeparatesBusinessBytesFromWireBytes() throws IOException {
        String schema = read("db/schema.sql");
        String delivery = compact(tableDefinition(schema, "agent_command_delivery"));
        String outbox = compact(tableDefinition(schema, "agent_outbox_event"));
        String inbox = compact(tableDefinition(schema, "agent_consumer_inbox"));

        assertTrue(delivery.contains("command_payload mediumblob not null"));
        assertTrue(delivery.contains("command_payload_hash binary(32) not null"));
        assertFalse(delivery.contains("wire_payload"));

        for (String wireTable : List.of(outbox, inbox)) {
            assertTrue(wireTable.contains("wire_payload mediumblob not null"));
            assertTrue(wireTable.contains("wire_payload_hash binary(32) not null"));
            assertFalse(wireTable.contains("command_payload"));
            assertFalse(wireTable.contains("payload mediumtext"));
            assertFalse(wireTable.contains("payload_hash varchar"));
        }
    }

    @Test
    void tablesReserveRetryLeaseCasExpiryFencingAndReplayFields() throws IOException {
        String schema = read("db/schema.sql");
        for (String table : TABLES) {
            String definition = compact(tableDefinition(schema, table));
            for (String required : List.of(
                    "status varchar(32) not null", "attempt_count int not null default 0",
                    "next_retry_at bigint default null", "lease_owner varchar(100) default null",
                    "lease_until bigint default null", "active_attempt int not null default 0",
                    "expires_at bigint not null", "version bigint not null default 0",
                    "replay_parent_message_id varchar(100) default null",
                    "replay_requester_id varchar(100) default null",
                    "replay_approver_id varchar(100) default null",
                    "replay_reason varchar(1000) default null")) {
                assertTrue(definition.contains(required), table + ": " + required);
            }
            assertTrue(definition.contains("idx_"), table);
            assertTrue(definition.contains("lease"), table);
            assertTrue(definition.contains("retry"), table);
            assertTrue(definition.contains("charset=utf8mb4 collate=utf8mb4_0900_bin"), table);
            assertFalse(definition.contains("foreign key"), table);
        }

        String delivery = compact(tableDefinition(schema, "agent_command_delivery"));
        assertTrue(delivery.contains("active_message_id varchar(100) default null"));
        assertTrue(delivery.contains("waiting_agent"));
        assertTrue(delivery.contains(
                "key idx_delivery_active_message (tenant_id, client_id, active_message_id, active_attempt)"));

        String inbox = compact(tableDefinition(schema, "agent_consumer_inbox"));
        assertTrue(inbox.contains("waiting_agent"));
    }

    @Test
    void publisherConfirmAndMandatoryReturnHaveIndependentDispositionColumns() throws IOException {
        String outbox = compact(tableDefinition(read("db/schema.sql"), "agent_outbox_event"));

        for (String confirm : List.of(
                "publisher_confirm_status varchar(20) not null default 'none'",
                "confirmed_at bigint default null", "confirm_error varchar(2000) default null")) {
            assertTrue(outbox.contains(confirm), confirm);
        }
        for (String returned : List.of(
                "mandatory_return_status varchar(20) not null default 'none'",
                "returned_at bigint default null", "return_reply_code int default null",
                "return_reply_text varchar(1000) default null")) {
            assertTrue(outbox.contains(returned), returned);
        }
        assertFalse(outbox.contains("confirm_or_return"));
    }

    @Test
    void migrationIsDdlOnlyAndContainsNoBackfillOrBusinessWrite() throws IOException {
        String migration = stripLineComments(read("db/agent-command-transport-schema.sql"));
        assertFalse(Pattern.compile("\\binsert\\s+into\\b").matcher(migration).find());
        assertFalse(Pattern.compile("\\bupdate\\s+[`a-z0-9_]+\\s+set\\b").matcher(migration).find());
        assertFalse(Pattern.compile("\\bdelete\\s+from\\b").matcher(migration).find());
        assertFalse(Pattern.compile("\\breplace\\s+into\\b").matcher(migration).find());
        assertFalse(migration.contains("references "));
    }

    private String read(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }

    private String tableDefinition(String sql, String table) {
        int start = sql.indexOf("create table if not exists " + table);
        assertTrue(start >= 0, table);
        boolean quoted = false;
        for (int end = start; end < sql.length(); end++) {
            char character = sql.charAt(end);
            if (character == '\'' && (end == 0 || sql.charAt(end - 1) != '\\')) {
                quoted = !quoted;
            }
            if (character == ';' && !quoted) {
                return sql.substring(start, end);
            }
        }
        throw new AssertionError("No semicolon after table: " + table);
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String stripLineComments(String value) {
        return value.replaceAll("(?m)^\\s*--.*$", " ");
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
