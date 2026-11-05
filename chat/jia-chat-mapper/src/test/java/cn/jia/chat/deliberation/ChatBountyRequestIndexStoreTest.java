package cn.jia.chat.deliberation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBountyRequestIndexStoreTest {
    private JdbcTemplate jdbc;
    private ChatBountyRequestIndexStore store;
    private final ChatBountyRequestIndexStore.Scope scope =
            new ChatBountyRequestIndexStore.Scope("tenant", "Owner", "client", "101", 1);

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:hall_catalog_page;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = spy(new JdbcTemplate(source));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE chat_request(id BIGINT PRIMARY KEY, tenant_id VARCHAR_IGNORECASE(50),
                  owner_jiacn VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  request_id VARCHAR_IGNORECASE(100),request_revision BIGINT,conversation_id VARCHAR(100),
                  conversation_generation BIGINT,user_message_id BIGINT,aggregate_state VARCHAR(30),state_version BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE chat_turn(turn_id VARCHAR(100),tenant_id VARCHAR_IGNORECASE(50),
                  owner_jiacn VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),request_id VARCHAR_IGNORECASE(100),
                  request_revision BIGINT,conversation_id VARCHAR(100),conversation_generation BIGINT,
                  created_at BIGINT,target_agent_id VARCHAR(100))
                """);
        jdbc.execute("""
                CREATE TABLE chat_interaction_step(step_id VARCHAR(100),tenant_id VARCHAR_IGNORECASE(50),
                  owner_jiacn VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),request_id VARCHAR_IGNORECASE(100),
                  request_revision BIGINT,conversation_id VARCHAR(100),conversation_generation BIGINT,
                  step_number BIGINT,task_id VARCHAR(100),assignment_revision BIGINT,target_agent_id VARCHAR(100),
                  kind VARCHAR(30),state VARCHAR(30),state_version BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE chat_step_execution_link(step_id VARCHAR(100),tenant_id VARCHAR_IGNORECASE(50),
                  owner_jiacn VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  execution_intent_id VARCHAR(100),execution_id VARCHAR(100),state VARCHAR(30))
                """);
        jdbc.execute("""
                CREATE TABLE chat_conversation_event(event_sequence BIGINT PRIMARY KEY,
                  tenant_id VARCHAR_IGNORECASE(50),owner_jiacn VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  conversation_id VARCHAR(100),conversation_generation BIGINT)
                """);
        store = new ChatBountyRequestIndexStore(jdbc);
    }
    private ChatBountyRequestIndexStore.Row row(long id) {
        return new ChatBountyRequestIndexStore.Row(id, "tenant", "Owner", "client", "r" + id, 1, "101", 1);
    }
    private void insert(long id) {
        jdbc.update("INSERT INTO chat_request VALUES(?, 'tenant','Owner','client',?,1,'101',1,?,'RUNNING',2)", id, "r" + id, id);
        jdbc.update("INSERT INTO chat_turn VALUES(?,'tenant','Owner','client',?,1,'101',1,1,'agent')", "t" + id, "r" + id);
        jdbc.update("INSERT INTO chat_interaction_step VALUES(?,'tenant','Owner','client',?,1,'101',1,1,'task',0,'agent','EXECUTE','RUNNING',1)", "s" + id, "r" + id);
        jdbc.update("INSERT INTO chat_step_execution_link VALUES(?,'tenant','Owner','client',?,?,'RUNNING')", "s" + id, "intent" + id, "execution" + id);
    }
    private long queryCount() {
        return mockingDetails(jdbc).getInvocations().stream().filter(call -> call.getMethod().getName().equals("query")
                && call.getMethod().getParameterCount() == 3
                && call.getMethod().getParameterTypes()[1] == org.springframework.jdbc.core.RowMapper.class).count();
    }
    @Test void oneAndHundredRequestPagesUseThreeQueriesAndOnlySelectedParents() {
        for (long id = 1; id <= 101; id++) insert(id);
        for (int size : List.of(1, 100)) {
            clearInvocations(jdbc);
            var result = store.projections(scope, LongStream.rangeClosed(1, size).mapToObj(this::row).toList());
            assertEquals(size, result.requests().size()); assertEquals(size, result.turns().size());
            assertEquals(size, result.steps().size()); assertEquals(3, queryCount());
            assertEquals("execution1", result.steps().getFirst().executionId());
            assertEquals("RUNNING", result.requests().getFirst().getAggregateState());
            assertFalse(result.turns().stream().anyMatch(turn -> "r101".equals(turn.getRequestId())));
        }
    }
    @Test void excludesForeignCaseOwnerGenerationRevisionAndParent() {
        insert(1); insert(2);
        jdbc.update("INSERT INTO chat_turn VALUES('foreign-owner','tenant','owner','client','r1',1,'101',1,1,'agent')");
        jdbc.update("INSERT INTO chat_turn VALUES('foreign-generation','tenant','Owner','client','r1',1,'101',2,1,'agent')");
        jdbc.update("INSERT INTO chat_turn VALUES('foreign-revision','tenant','Owner','client','r1',2,'101',1,1,'agent')");
        jdbc.update("INSERT INTO chat_turn VALUES('foreign-request','tenant','Owner','client','R1',1,'101',1,1,'agent')");
        var result = store.projections(scope, List.of(row(1)));
        assertEquals(List.of("t1"), result.turns().stream().map(ChatTurnEntity::getTurnId).toList());
        jdbc.update("UPDATE chat_request SET owner_jiacn='owner' WHERE id=1");
        result = store.projections(scope, List.of(row(1)));
        assertTrue(result.requests().isEmpty()); assertTrue(result.turns().isEmpty()); assertTrue(result.steps().isEmpty());
    }
    @Test void mutableExecutionProgressIsReadFreshAndForeignLinkIsNotAttached() {
        insert(1);
        assertEquals("RUNNING", store.projections(scope, List.of(row(1))).steps().getFirst().executionState());
        jdbc.update("UPDATE chat_step_execution_link SET state='COMPLETED'");
        assertEquals("COMPLETED", store.projections(scope, List.of(row(1))).steps().getFirst().executionState());
        jdbc.update("UPDATE chat_step_execution_link SET owner_jiacn='owner'");
        assertNull(store.projections(scope, List.of(row(1))).steps().getFirst().executionIntentId());
    }
    @Test void emptyPageHasNoProjectionQueries() {
        clearInvocations(jdbc);
        assertTrue(store.projections(scope, List.of()).requests().isEmpty());
        verifyNoInteractions(jdbc);
    }
    @Test void targetedDiscoveryKeepsExactScopeAndRequestIdentity() {
        insert(1); insert(2);
        assertEquals(List.of(row(1)), store.byRequest(scope, "r1"));
        assertTrue(store.byRequest(scope, "R1").isEmpty());
        assertTrue(store.byRequest(new ChatBountyRequestIndexStore.Scope("tenant", "owner", "client", "101", 1), "r1").isEmpty());
        assertTrue(store.byRequest(new ChatBountyRequestIndexStore.Scope("tenant", "Owner", "client", "102", 1), "r1").isEmpty());
    }
    @Test void latestSelectsOneOrdinalAfterExactScopeFiltering() {
        assertTrue(store.latest(scope).isEmpty());
        for (long id = 1; id <= 105; id++) insert(id);
        jdbc.update("UPDATE chat_request SET conversation_id='102' WHERE id=105");
        jdbc.update("UPDATE chat_request SET conversation_generation=2 WHERE id=104");
        jdbc.update("UPDATE chat_request SET owner_jiacn='owner' WHERE id=103");
        jdbc.update("UPDATE chat_request SET client_id='Client' WHERE id=102");
        jdbc.update("UPDATE chat_request SET tenant_id='Tenant' WHERE id=101");
        clearInvocations(jdbc);
        assertEquals(List.of(row(100)), store.latest(scope));
        assertEquals(1, queryCount());
    }

    @Test void recoveryCursorIsExactScopedAndDoesNotReadEventBodies() {
        assertEquals(0, store.eventCursor(scope));
        jdbc.update("INSERT INTO chat_conversation_event VALUES(9007199254740993,'tenant','Owner','client','101',1)");
        jdbc.update("INSERT INTO chat_conversation_event VALUES(9007199254740994,'tenant','owner','client','101',1)");
        jdbc.update("INSERT INTO chat_conversation_event VALUES(9007199254740995,'tenant','Owner','client','102',1)");
        jdbc.update("INSERT INTO chat_conversation_event VALUES(9007199254740996,'tenant','Owner','client','101',2)");
        assertEquals(9007199254740993L, store.eventCursor(scope));
    }

}
