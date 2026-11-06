package cn.jia.chat.deliberation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual JDBC mapping, not a mocked outcome store. MySQL index plans need separate verification. */
class ChatTypedOutcomeDeliveryIndexTest {
    private JdbcTemplate jdbc;
    private JdbcChatTypedDeliberationStore store;
    private final ChatTypedDeliberationStore.Scope scope =
            new ChatTypedDeliberationStore.Scope("tenant", "Owner", "client", "101", 1);

    @BeforeEach void setup() {
        jdbc = spy(new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:typed_delivery_index;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            // H2 lacks MySQL's unary BINARY spelling. Keep byte-exact comparison via
            // equivalent CAST for this mapping test; production SQL is exercised on MySQL.
            @Override public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                return super.query(sql.replaceAll("BINARY ([a-z_]+|\\?)", "CAST($1 AS BINARY)"), mapper, args);
            }
        });
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE chat_typed_outcome (
                  outcome_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR_IGNORECASE(50),
                  owner_jiacn VARCHAR_IGNORECASE(50), client_id VARCHAR_IGNORECASE(50),
                  conversation_id VARCHAR_IGNORECASE(100), conversation_generation BIGINT,
                  request_id VARCHAR_IGNORECASE(100), request_revision BIGINT, turn_id VARCHAR_IGNORECASE(64),
                  task_id VARCHAR(100), assignment_revision BIGINT, assistant_message_id BIGINT,
                  final_digest VARCHAR(100), kind VARCHAR(30), text CLOB, binding_json CLOB,
                  facts_json CLOB, outcome_json CLOB, source_catalog_json CLOB, created_at BIGINT,
                  delivery_target_agent_id VARCHAR(100), delivery_parent_outcome_id VARCHAR(64))
                """);
        store = new JdbcChatTypedDeliberationStore(jdbc);
        clearInvocations(jdbc);
    }

    @Test void metadataUsesSameInsertAndRoundTripsThroughAllExistingReaders() {
        var row = new ChatTypedDeliberationStore.Outcome("outcome", scope, "request", 1, "turn", "task", 3,
                101, "sha256:original", "ANSWER", "text", "{}", "{}", "{\"original\":true}", "[]", 1,
                "wuyong", "parent");
        assertEquals(1, store.insertOutcome(row));
        // No metadata SELECT/UPDATE accompanies the existing outcome INSERT.
        assertEquals(1, mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update")
                        && call.getMethod().getParameterTypes()[0] == String.class
                        && call.getMethod().getParameterTypes()[1] == Object[].class).count());
        assertFalse(mockingDetails(jdbc).getInvocations().stream()
                .anyMatch(call -> call.getMethod().getName().startsWith("query")));
        assertEquals(row, store.findOutcome(scope, "outcome", false));
        assertEquals(row, store.findOutcomeByTurn(scope, "turn", false));
        assertEquals(row, store.findOutcomeByRequest(scope, "request"));
        assertNull(store.findOutcome(new ChatTypedDeliberationStore.Scope("tenant", "owner", "client", "101", 1), "outcome", false));
        assertNull(store.findOutcome(new ChatTypedDeliberationStore.Scope("tenant", "Owner", "client", "101", 2), "outcome", false));
    }

    @Test void historicalConstructorDoesNotInventDeliveryMetadata() {
        var row = new ChatTypedDeliberationStore.Outcome("old", scope, "request", 1, "turn", "task", 3,
                101, "sha256:original", "ANSWER", "text", "{}", "{}", "{}", "[]", 1);
        assertEquals(1, store.insertOutcome(row));
        assertEquals(row, store.findOutcome(scope, "old", false));
        assertNull(row.deliveryTargetAgentId());
        assertNull(row.deliveryParentOutcomeId());
    }
}
