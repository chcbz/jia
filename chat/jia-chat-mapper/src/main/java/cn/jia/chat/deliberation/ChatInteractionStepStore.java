package cn.jia.chat.deliberation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** Durable interaction decisions: a step has at most one execution intent and one bound execution. */
@Repository
public class ChatInteractionStepStore {
    private final JdbcTemplate jdbc;

    public ChatInteractionStepStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Step(String stepId, String tenantId, String ownerJiacn, String clientId,
            String requestId, long requestRevision, long stepNumber, String conversationId,
            long conversationGeneration, String taskId, long assignmentRevision, String grantId,
            long grantVersion, String targetAgentId, String kind, String state, long stateVersion,
            String inputSnapshotDigest, long createdAt, long updatedAt) { }

    public record ExecutionLink(String executionIntentId, String tenantId, String ownerJiacn,
            String clientId, String stepId, String executionId, String state, long stateVersion,
            long createdAt, long updatedAt) { }

    private static Step step(ResultSet rs, int row) throws SQLException {
        return new Step(rs.getString("step_id"), rs.getString("tenant_id"), rs.getString("owner_jiacn"),
                rs.getString("client_id"), rs.getString("request_id"), rs.getLong("request_revision"),
                rs.getLong("step_number"), rs.getString("conversation_id"),
                rs.getLong("conversation_generation"), rs.getString("task_id"),
                rs.getLong("assignment_revision"), rs.getString("grant_id"), rs.getLong("grant_version"),
                rs.getString("target_agent_id"), rs.getString("kind"), rs.getString("state"),
                rs.getLong("state_version"), rs.getString("input_snapshot_digest"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static ExecutionLink link(ResultSet rs, int row) throws SQLException {
        return new ExecutionLink(rs.getString("execution_intent_id"), rs.getString("tenant_id"),
                rs.getString("owner_jiacn"), rs.getString("client_id"), rs.getString("step_id"),
                rs.getString("execution_id"), rs.getString("state"), rs.getLong("state_version"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    public Step findStep(String tenantId, String ownerJiacn, String clientId,
            String requestId, long requestRevision, long stepNumber) {
        List<Step> rows = jdbc.query("""
                SELECT * FROM chat_interaction_step WHERE tenant_id=? AND owner_jiacn=? AND client_id=?
                  AND request_id=? AND request_revision=? AND step_number=?
                """, ChatInteractionStepStore::step, tenantId, ownerJiacn, clientId, requestId,
                requestRevision, stepNumber);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Step> findSteps(String tenantId, String ownerJiacn, String clientId,
            String requestId, long requestRevision) {
        return jdbc.query("""
                SELECT * FROM chat_interaction_step WHERE tenant_id=? AND owner_jiacn=? AND client_id=?
                  AND request_id=? AND request_revision=? ORDER BY step_number
                """, ChatInteractionStepStore::step, tenantId, ownerJiacn, clientId,
                requestId, requestRevision);
    }

    public int insertStep(Step step) {
        return jdbc.update("""
                INSERT INTO chat_interaction_step (step_id,tenant_id,owner_jiacn,client_id,request_id,
                  request_revision,step_number,conversation_id,conversation_generation,task_id,
                  assignment_revision,grant_id,grant_version,target_agent_id,kind,state,state_version,
                  input_snapshot_digest,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, step.stepId(), step.tenantId(), step.ownerJiacn(), step.clientId(),
                step.requestId(), step.requestRevision(), step.stepNumber(), step.conversationId(),
                step.conversationGeneration(), step.taskId(), step.assignmentRevision(), step.grantId(),
                step.grantVersion(), step.targetAgentId(), step.kind(), step.state(), step.stateVersion(),
                step.inputSnapshotDigest(), step.createdAt(), step.updatedAt());
    }

    public int updateStepState(Step step, String nextState, long now) {
        return jdbc.update("""
                UPDATE chat_interaction_step SET state=?,state_version=state_version+1,updated_at=?
                WHERE step_id=? AND tenant_id=? AND owner_jiacn=? AND client_id=? AND state_version=?
                """, nextState, now, step.stepId(), step.tenantId(), step.ownerJiacn(),
                step.clientId(), step.stateVersion());
    }

    public ExecutionLink findLink(String tenantId, String ownerJiacn, String clientId, String stepId) {
        List<ExecutionLink> rows = jdbc.query("""
                SELECT * FROM chat_step_execution_link WHERE tenant_id=? AND owner_jiacn=?
                  AND client_id=? AND step_id=?
                """, ChatInteractionStepStore::link, tenantId, ownerJiacn, clientId, stepId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public int insertLink(ExecutionLink link) {
        return jdbc.update("""
                INSERT INTO chat_step_execution_link (execution_intent_id,tenant_id,owner_jiacn,
                  client_id,step_id,execution_id,state,state_version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, link.executionIntentId(), link.tenantId(), link.ownerJiacn(), link.clientId(),
                link.stepId(), link.executionId(), link.state(), link.stateVersion(),
                link.createdAt(), link.updatedAt());
    }

    /** Non-zero only for the first exact binding; callers must reconcile unknown outcomes. */
    public int bindExecution(ExecutionLink link, String executionId, long now) {
        return jdbc.update("""
                UPDATE chat_step_execution_link SET execution_id=?,state='RUNNING',
                  state_version=state_version+1,updated_at=?
                WHERE execution_intent_id=? AND tenant_id=? AND owner_jiacn=? AND client_id=?
                  AND step_id=? AND execution_id IS NULL AND state_version=?
                """, executionId, now, link.executionIntentId(), link.tenantId(),
                link.ownerJiacn(), link.clientId(), link.stepId(), link.stateVersion());
    }
}
