package cn.jia.chat.deliberation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL against isolated rows: no heavyweight final reader or mocked JDBC result. */
class ChatDeliveryContextStoreTest {
    private JdbcTemplate jdbc;
    private ChatDeliveryContextStore store;
    private final ChatDeliveryContextStore.Scope scope = new ChatDeliveryContextStore.Scope("0", "owner", "client", "101", 1, "task", 3, "wuyong");
    private static final String OWNER = "tenant_id VARCHAR(50),owner_jiacn VARCHAR(50),client_id VARCHAR(50),";
    private static final String SCOPE = OWNER + "conversation_id VARCHAR(100),conversation_generation BIGINT,";
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:delivery_context;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE chat_typed_outcome(" + SCOPE + "outcome_id VARCHAR(64) PRIMARY KEY,request_id VARCHAR(100),request_revision BIGINT,turn_id VARCHAR(64),task_id VARCHAR(100),assignment_revision BIGINT,assistant_message_id BIGINT,final_digest VARCHAR(100),kind VARCHAR(30),text CLOB,delivery_target_agent_id VARCHAR(100),delivery_parent_outcome_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE chat_turn(" + SCOPE + "turn_id VARCHAR(64),request_id VARCHAR(100),request_revision BIGINT,dispatch_id VARCHAR(100),route VARCHAR(30),state VARCHAR(30),final_message_id BIGINT,target_agent_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE chat_dispatch_outbox(" + OWNER + "event_id VARCHAR(64),turn_id VARCHAR(64),dispatch_id VARCHAR(100),event_type VARCHAR(40),status VARCHAR(30))");
        jdbc.execute("CREATE TABLE chat_typed_admission(" + SCOPE + "idempotency_key VARCHAR(100),parent_outcome_id VARCHAR(64),request_id VARCHAR(100),request_revision BIGINT,task_id VARCHAR(100),assignment_revision BIGINT)");
        jdbc.execute("CREATE TABLE chat_request(" + SCOPE + "request_id VARCHAR(100),request_revision BIGINT,aggregate_state VARCHAR(30))");
        jdbc.execute("CREATE TABLE chat_interaction_step(" + SCOPE + "request_id VARCHAR(100),request_revision BIGINT,step_id VARCHAR(64),step_number BIGINT,task_id VARCHAR(100),assignment_revision BIGINT,target_agent_id VARCHAR(100),kind VARCHAR(30),state VARCHAR(30))");
        jdbc.execute("CREATE TABLE chat_step_execution_link(" + OWNER + "step_id VARCHAR(64),state VARCHAR(30),execution_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE chat_bounty_binding(" + OWNER + "task_id VARCHAR(100),conversation_id BIGINT,assignment_revision BIGINT)");
        store = new ChatDeliveryContextStore(jdbc);
    }
    private void outcome(String id, long message, String parent, String kind) {
        jdbc.update("INSERT INTO chat_typed_outcome VALUES('0','owner','client','101',1,?,?,1,?,'task',3,?,? ,?,?,'wuyong',?)",
                id, "r-"+id, "t-"+id, message, "sha256:"+"a".repeat(64), kind, "x".repeat(1000), parent);
        jdbc.update("INSERT INTO chat_turn VALUES('0','owner','client','101',1,?,?,1,?,'CHAT','PUBLISHED',?,'wuyong')", "t-"+id, "r-"+id, "d-"+id, message);
    }
    private void execution(String id, String state) {
        jdbc.update("INSERT INTO chat_dispatch_outbox VALUES('0','owner','client',?,?,?,'ACTION_REQUESTED','SENT')", "e-"+id, "t-"+id, "d-"+id);
        jdbc.update("INSERT INTO chat_typed_admission VALUES('0','owner','client','101',1,?,?,?,1,'task',3)", "e-"+id, id, "exec-"+id);
        jdbc.update("INSERT INTO chat_request VALUES('0','owner','client','101',1,?,1,?)", "exec-"+id, state);
        jdbc.update("INSERT INTO chat_interaction_step VALUES('0','owner','client','101',1,?,1,?,1,'task',3,'wuyong','EXECUTE',?)", "exec-"+id, "s-"+id, state);
        jdbc.update("INSERT INTO chat_step_execution_link VALUES('0','owner','client',?,?,?)", "s-"+id, state, "execution-"+id);
    }
    private List<String> heads() { return store.heads(scope, null, 51).stream().map(ChatDeliveryContextStore.Choice::outcomeId).toList(); }

    @Test void independentRootsAndBranchesRemainChoicesAndOnlyCommittedChildrenHideParents() {
        outcome("root",1,null,"ANSWER");outcome("branch-a",2,"root","ANSWER");outcome("branch-b",3,"root","ANSWER");outcome("independent",4,null,"ANSWER");
        assertEquals(List.of("independent","branch-b","branch-a"),heads());
        outcome("pending",5,"branch-a","ACTION_REQUEST");execution("pending","RUNNING");
        assertEquals(List.of("independent","branch-b","branch-a"),heads());assertTrue(store.pendingExecution(scope));
        jdbc.update("UPDATE chat_request SET aggregate_state='FAILED' WHERE request_id='exec-pending'");
        assertFalse(store.pendingExecution(scope));assertTrue(heads().contains("branch-a"));
        jdbc.update("UPDATE chat_request SET aggregate_state='CANCELLED' WHERE request_id='exec-pending'");
        assertFalse(store.pendingExecution(scope));assertTrue(heads().contains("branch-a"));
        jdbc.update("UPDATE chat_request SET aggregate_state='RESULT_COMMITTED' WHERE request_id='exec-pending'");
        assertTrue(heads().contains("branch-a"));assertTrue(store.pendingExecution(scope));
        jdbc.update("UPDATE chat_interaction_step SET state='RESULT_COMMITTED'");jdbc.update("UPDATE chat_step_execution_link SET state='RESULT_COMMITTED'");
        assertEquals(List.of("pending","independent","branch-b"),heads());assertFalse(store.pendingExecution(scope));
    }
    @Test void exactScopeAssignmentAndAgentDoNotHideAnotherParentsChoices() {
        outcome("root",1,null,"ANSWER");outcome("foreign",2,"root","ANSWER");
        for (String change : List.of("owner_jiacn='Owner'","conversation_generation=2","assignment_revision=4","delivery_target_agent_id='likui'","task_id='other'")) {
            jdbc.update("UPDATE chat_typed_outcome SET "+change+" WHERE outcome_id='foreign'");
            assertEquals(List.of("root"),heads());
            jdbc.update("UPDATE chat_typed_outcome SET owner_jiacn='owner',conversation_generation=1,assignment_revision=3,delivery_target_agent_id='wuyong',task_id='task' WHERE outcome_id='foreign'");
        }
    }
    @Test void completedExecutionRequiresExactBoundEventStepAgentAndLink() {
        outcome("action",1,null,"ACTION_REQUEST");execution("action","RESULT_COMMITTED");
        assertEquals(List.of("action"),heads());
        jdbc.update("UPDATE chat_interaction_step SET target_agent_id='likui'");assertEquals(List.of(),heads());
        jdbc.update("UPDATE chat_interaction_step SET target_agent_id='wuyong'");
        jdbc.update("UPDATE chat_typed_admission SET idempotency_key='unrelated'");assertEquals(List.of(),heads());
        jdbc.update("UPDATE chat_typed_admission SET idempotency_key='e-action'");
        jdbc.update("DELETE FROM chat_step_execution_link");assertEquals(List.of(),heads());assertTrue(store.pendingExecution(scope));
    }
    @Test void queuedAndDeadDispatchAreNotCompletedDeliveries() {
        outcome("action",1,null,"ACTION_REQUEST");
        assertTrue(store.pendingExecution(scope));assertEquals(List.of(),heads());
        jdbc.update("INSERT INTO chat_dispatch_outbox VALUES('0','owner','client','event','t-action','d-action','ACTION_REQUESTED','READY')");
        assertTrue(store.pendingExecution(scope));assertEquals(List.of(),heads());
        jdbc.update("UPDATE chat_dispatch_outbox SET status='DEAD'");
        assertFalse(store.pendingExecution(scope));assertEquals(List.of(),heads());
    }
    @Test void assignmentComesOnlyFromTheOwnerBoundConversation() {
        jdbc.update("INSERT INTO chat_bounty_binding VALUES('0','owner','client','task',101,3)");
        assertEquals(Long.valueOf(3),store.assignment("0","owner","client","task",101));
        assertNull(store.assignment("0","Owner","client","task",101));
        assertNull(store.assignment("0","owner","client","task",102));
    }
    @Test void pagingUsesMessageAndIdTieBreakAndReturnsOnlyShortLabels() {
        outcome("a",9007199254740993L,null,"ANSWER");outcome("b",9007199254740993L,null,"ANSWER");outcome("c",9007199254740994L,null,"ANSWER");
        var first=store.heads(scope,null,2);assertEquals(List.of("c","b"),first.stream().map(ChatDeliveryContextStore.Choice::outcomeId).toList());
        assertEquals(60,first.getFirst().label().length());
        assertEquals(List.of("a"),store.heads(scope,new ChatDeliveryContextStore.Cursor(9007199254740993L,"b"),2).stream().map(ChatDeliveryContextStore.Choice::outcomeId).toList());
    }
}
