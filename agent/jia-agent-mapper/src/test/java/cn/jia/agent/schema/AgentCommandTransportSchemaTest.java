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
            "agent_command_delivery", "agent_outbox_event", "agent_consumer_inbox",
            "agent_command_operation_audit", "agent_command_redrive_operation");

    @Test
    void canonicalAndIndependentMigrationDeclareByteExactSameFiveTables() throws IOException {
        String schema = read("db/schema.sql");
        String migration = read("db/agent-command-transport-schema.sql");

        for (String table : TABLES) {
            assertEquals(compact(tableDefinition(schema, table)),
                    compact(tableDefinition(migration, table)), table);
        }
        assertEquals(5, occurrences(migration, "create table if not exists agent_"));
        for (String trigger : List.of(
                "trg_command_operation_audit_no_update",
                "trg_command_operation_audit_no_delete")) {
            assertEquals(compact(triggerDefinition(schema, trigger)),
                    compact(triggerDefinition(migration, trigger)), trigger);
            assertEquals(1, occurrences(migration, "create trigger " + trigger));
            assertEquals(1, occurrences(migration, "drop trigger if exists " + trigger));
        }
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
        for (String table : TABLES.subList(0, 3)) {
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
    void operationAuditIsPayloadFreeAppendOnlyShapedAndExactScoped() throws IOException {
        String schema = read("db/schema.sql");
        String audit = compact(tableDefinition(schema,
                "agent_command_operation_audit"));
        for (String required : List.of(
                "unique key uk_command_operation_phase (operation_id, phase)",
                "key idx_command_operation_scope (tenant_id, client_id, id)",
                "key idx_command_operation_outcome (tenant_id, client_id, operation_type, outcome, created_at, id)",
                "operation_type varchar(32) not null", "wire_hash binary(32) default null",
                "requester_id varchar(100) not null", "approver_id varchar(100) default null",
                "reason varchar(1000) not null", "ticket_reference varchar(200) not null",
                "outcome varchar(32) not null", "created_by varchar(100) not null")) {
            assertTrue(audit.contains(required), required);
        }
        for (String forbidden : List.of(
                "payload mediumblob", "payload mediumtext", "headers ", "credential ",
                "password ", "token ", "lease_owner", "lease_until")) {
            assertFalse(audit.contains(forbidden), forbidden);
        }
        assertTrue(compact(triggerDefinition(schema,
                "trg_command_operation_audit_no_update")).contains(
                "before update on agent_command_operation_audit for each row signal sqlstate '45000'"));
        assertTrue(compact(triggerDefinition(schema,
                "trg_command_operation_audit_no_delete")).contains(
                "before delete on agent_command_operation_audit for each row signal sqlstate '45000'"));
    }

    @Test
    void redriveOperationHasExactStatesGeneratedGuardsAndPayloadFreeScopedIndexes()
            throws IOException {
        String operation = compact(tableDefinition(
                read("db/schema.sql"), "agent_command_redrive_operation"));
        for (String required : List.of(
                "outcome_state enum('pending','succeeded','failed') not null default 'pending'",
                "settlement_state enum('pending','source_acked','source_requeued','not_acquired','unknown') not null default 'pending'",
                "disposition_guard tinyint generated always as (if(outcome_state='pending',1,null)) stored",
                "redrive_guard tinyint generated always as (if(settlement_state in ('source_requeued','not_acquired'),null,1)) stored",
                "unique key uk_redrive_operation_id (tenant_id, client_id, operation_id)",
                "unique key uk_redrive_operation_guard (tenant_id, client_id, delivery_id, source_message_id, source_attempt, redrive_guard)",
                "key idx_redrive_operation_disposition (tenant_id, client_id, delivery_id, source_message_id, source_attempt, disposition_guard)",
                "key idx_redrive_operation_recovery (tenant_id, client_id, outcome_state, requested_at, id)",
                "key idx_redrive_operation_scope (tenant_id, client_id, id)",
                "charset=utf8mb4 collate=utf8mb4_0900_bin")) {
            assertTrue(operation.contains(required), required);
        }
        for (String forbidden : List.of(
                "wire_payload", "command_payload", "raw_payload", "headers ",
                "credential ", "password ", "token ", "foreign key")) {
            assertFalse(operation.contains(forbidden), forbidden);
        }
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

    private String triggerDefinition(String sql, String trigger) {
        int start = sql.indexOf("create trigger " + trigger);
        assertTrue(start >= 0, trigger);
        boolean quoted = false;
        for (int end = start; end < sql.length(); end++) {
            char character = sql.charAt(end);
            if (character == '\'' && (end == 0 || sql.charAt(end - 1) != '\\')) {
                quoted = !quoted;
            }
            if (character == ';' && !quoted) return sql.substring(start, end);
        }
        throw new AssertionError("No semicolon after trigger: " + trigger);
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
