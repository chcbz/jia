package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Objects;

@Named
public final class JdbcChatTypedDeliberationStore implements ChatTypedDeliberationStore {
    private static final String SCOPE = " tenant_id=? AND BINARY tenant_id=BINARY ?"
            + " AND owner_jiacn=? AND BINARY owner_jiacn=BINARY ?"
            + " AND client_id=? AND BINARY client_id=BINARY ?"
            + " AND conversation_id=? AND BINARY conversation_id=BINARY ?"
            + " AND conversation_generation=? ";
    private final JdbcTemplate jdbc;

    public JdbcChatTypedDeliberationStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Outcome findOutcomeByTurn(Scope s, String turnId, boolean lock) {
        return one("SELECT * FROM chat_typed_outcome WHERE" + SCOPE
                + "AND turn_id=? AND BINARY turn_id=BINARY ?" + suffix(lock), outcomeMapper(), args(s, turnId, turnId));
    }
    @Override public Outcome findOutcomeByRequest(Scope s, String requestId) {
        return one("SELECT * FROM chat_typed_outcome WHERE" + SCOPE
                + "AND request_id=? AND BINARY request_id=BINARY ? LIMIT 2", outcomeMapper(), args(s, requestId, requestId));
    }
    @Override public Outcome findOutcome(Scope s, String outcomeId, boolean lock) {
        return one("SELECT * FROM chat_typed_outcome WHERE" + SCOPE
                + "AND outcome_id=? AND BINARY outcome_id=BINARY ?" + suffix(lock), outcomeMapper(), args(s, outcomeId, outcomeId));
    }
    @Override public int insertOutcome(Outcome o) {
        return jdbc.update("""
                INSERT INTO chat_typed_outcome
                (outcome_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,
                 request_id,request_revision,turn_id,task_id,assignment_revision,assistant_message_id,
                 final_digest,kind,text,binding_json,facts_json,outcome_json,source_catalog_json,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, o.outcomeId(), o.scope().tenantId(), o.scope().ownerJiacn(), o.scope().clientId(),
                o.scope().conversationId(), o.scope().conversationGeneration(), o.requestId(), o.requestRevision(),
                o.turnId(), o.taskId(), o.assignmentRevision(), o.assistantMessageId(), o.finalDigest(), o.kind(),
                o.text(), o.bindingJson(), o.factsJson(), o.outcomeJson(), o.sourceCatalogJson(), o.createdAt());
    }

    @Override public PendingQuestion findPendingByOutcome(Scope s, String outcomeId, boolean lock) {
        return one("SELECT * FROM chat_typed_pending_question WHERE" + SCOPE
                + "AND outcome_id=? AND BINARY outcome_id=BINARY ?" + suffix(lock), pendingMapper(), args(s, outcomeId, outcomeId));
    }
    @Override public PendingQuestion findPending(Scope s, String pendingId, boolean lock) {
        return one("SELECT * FROM chat_typed_pending_question WHERE" + SCOPE
                + "AND pending_question_id=? AND BINARY pending_question_id=BINARY ?" + suffix(lock), pendingMapper(), args(s, pendingId, pendingId));
    }
    @Override public int insertPending(PendingQuestion p) {
        return jdbc.update("""
                INSERT INTO chat_typed_pending_question
                (pending_question_id,outcome_id,tenant_id,owner_jiacn,client_id,conversation_id,
                 conversation_generation,state,state_version,question,required_facts_json,reply_request_id,
                 reply_idempotency_key,reply_body_digest,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, p.pendingQuestionId(), p.outcomeId(), p.scope().tenantId(), p.scope().ownerJiacn(),
                p.scope().clientId(), p.scope().conversationId(), p.scope().conversationGeneration(), p.state(),
                p.stateVersion(), p.question(), p.requiredFactsJson(), p.replyRequestId(), p.replyIdempotencyKey(),
                p.replyBodyDigest(), p.createdAt(), p.updatedAt());
    }
    @Override public int answerPending(PendingQuestion p, long expected, String requestId,
            String key, String digest, long now) {
        return jdbc.update("""
                UPDATE chat_typed_pending_question SET state='ANSWERED',state_version=state_version+1,
                  reply_request_id=?,reply_idempotency_key=?,reply_body_digest=?,updated_at=?
                WHERE pending_question_id=? AND BINARY pending_question_id=BINARY ?
                  AND tenant_id=? AND BINARY tenant_id=BINARY ? AND owner_jiacn=? AND BINARY owner_jiacn=BINARY ?
                  AND client_id=? AND BINARY client_id=BINARY ? AND conversation_id=? AND BINARY conversation_id=BINARY ?
                  AND conversation_generation=? AND state='OPEN' AND state_version=?
                """, requestId, key, digest, now, p.pendingQuestionId(), p.pendingQuestionId(),
                p.scope().tenantId(), p.scope().tenantId(), p.scope().ownerJiacn(), p.scope().ownerJiacn(),
                p.scope().clientId(), p.scope().clientId(), p.scope().conversationId(), p.scope().conversationId(),
                p.scope().conversationGeneration(), expected);
    }

    @Override public Proposal findProposalByOutcome(Scope s, String outcomeId) {
        return one("SELECT * FROM chat_typed_proposal WHERE" + SCOPE
                + "AND outcome_id=? AND BINARY outcome_id=BINARY ? LIMIT 2", proposalMapper(), args(s, outcomeId, outcomeId));
    }
    @Override public int insertProposal(Proposal p) {
        return jdbc.update("""
                INSERT INTO chat_typed_proposal
                (proposal_id,outcome_id,tenant_id,owner_jiacn,client_id,conversation_id,
                 conversation_generation,state,state_version,operation,instruction,source_ref_ids_json,
                 source_selectors_json,parent_request_id,parent_step_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, p.proposalId(), p.outcomeId(), p.scope().tenantId(), p.scope().ownerJiacn(),
                p.scope().clientId(), p.scope().conversationId(), p.scope().conversationGeneration(), p.state(),
                p.stateVersion(), p.operation(), p.instruction(), p.sourceRefIdsJson(), p.sourceSelectorsJson(),
                p.parentRequestId(), p.parentStepId(), p.createdAt());
    }

    @Override public Admission findAdmissionByKey(Scope s, String key, boolean lock) {
        return one("SELECT * FROM chat_typed_admission WHERE" + SCOPE
                + "AND idempotency_key=? AND BINARY idempotency_key=BINARY ?" + suffix(lock), admissionMapper(), args(s, key, key));
    }
    @Override public Admission findAdmissionByRequest(Scope s, String requestId) {
        return one("SELECT * FROM chat_typed_admission WHERE" + SCOPE
                + "AND request_id=? AND BINARY request_id=BINARY ? LIMIT 2", admissionMapper(), args(s, requestId, requestId));
    }
    @Override public int insertAdmission(Admission a) {
        return jdbc.update("""
                INSERT INTO chat_typed_admission
                (admission_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,
                 idempotency_key,request_digest,body_digest,intent,task_id,assignment_revision,parent_outcome_id,
                 pending_question_id,request_id,request_revision,user_message_id,turn_ids_json,source_catalog_json,state,
                 state_version,event_cursor,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, a.admissionId(), a.scope().tenantId(), a.scope().ownerJiacn(), a.scope().clientId(),
                a.scope().conversationId(), a.scope().conversationGeneration(), a.idempotencyKey(), a.requestDigest(),
                a.bodyDigest(), a.intent(), a.taskId(), a.assignmentRevision(), a.parentOutcomeId(), a.pendingQuestionId(),
                a.requestId(), a.requestRevision(), a.userMessageId(), a.turnIdsJson(), a.sourceCatalogJson(), a.state(),
                a.stateVersion(), a.eventCursor(), a.createdAt());
    }

    @Override public List<String> tableNames() {
        return List.of("chat_typed_outcome", "chat_typed_pending_question", "chat_typed_proposal", "chat_typed_admission");
    }

    private static String suffix(boolean lock) { return " LIMIT 2" + (lock ? " FOR UPDATE" : ""); }
    private Object[] args(Scope s, Object... tail) {
        Object[] values = new Object[9 + tail.length];
        values[0]=s.tenantId(); values[1]=s.tenantId(); values[2]=s.ownerJiacn(); values[3]=s.ownerJiacn();
        values[4]=s.clientId(); values[5]=s.clientId(); values[6]=s.conversationId(); values[7]=s.conversationId();
        values[8]=s.conversationGeneration(); System.arraycopy(tail,0,values,9,tail.length); return values;
    }
    private <T> T one(String sql, RowMapper<T> mapper, Object[] args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) throw new IllegalStateException("Typed deliberation scope is ambiguous");
        return rows.getFirst();
    }
    private RowMapper<Outcome> outcomeMapper() { return (r,n) -> new Outcome(r.getString("outcome_id"),
            scope(r),r.getString("request_id"),r.getLong("request_revision"),r.getString("turn_id"),
            r.getString("task_id"),r.getLong("assignment_revision"),r.getLong("assistant_message_id"),
            r.getString("final_digest"),r.getString("kind"),r.getString("text"),r.getString("binding_json"),
            r.getString("facts_json"),r.getString("outcome_json"),r.getString("source_catalog_json"),r.getLong("created_at")); }
    private RowMapper<PendingQuestion> pendingMapper() { return (r,n) -> new PendingQuestion(
            r.getString("pending_question_id"),r.getString("outcome_id"),scope(r),r.getString("state"),
            r.getLong("state_version"),r.getString("question"),r.getString("required_facts_json"),
            r.getString("reply_request_id"),r.getString("reply_idempotency_key"),r.getString("reply_body_digest"),
            r.getLong("created_at"),r.getLong("updated_at")); }
    private RowMapper<Proposal> proposalMapper() { return (r,n) -> new Proposal(r.getString("proposal_id"),
            r.getString("outcome_id"),scope(r),r.getString("state"),r.getLong("state_version"),
            r.getString("operation"),r.getString("instruction"),r.getString("source_ref_ids_json"),
            r.getString("source_selectors_json"),r.getString("parent_request_id"),r.getString("parent_step_id"),
            r.getLong("created_at")); }
    private RowMapper<Admission> admissionMapper() { return (r,n) -> new Admission(r.getString("admission_id"),
            scope(r),r.getString("idempotency_key"),r.getString("request_digest"),r.getString("body_digest"),r.getString("intent"),
            r.getString("task_id"),r.getLong("assignment_revision"),r.getString("parent_outcome_id"),
            r.getString("pending_question_id"),r.getString("request_id"),r.getLong("request_revision"),
            r.getLong("user_message_id"),r.getString("turn_ids_json"),r.getString("source_catalog_json"),r.getString("state"),
            r.getLong("state_version"),r.getLong("event_cursor"),r.getLong("created_at")); }
    private Scope scope(java.sql.ResultSet r) throws java.sql.SQLException { return new Scope(r.getString("tenant_id"),
            r.getString("owner_jiacn"),r.getString("client_id"),r.getString("conversation_id"),
            r.getLong("conversation_generation")); }
}
