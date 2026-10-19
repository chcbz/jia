package cn.jia.chat.service;

import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Persisted immutable intent and receipt; lookup methods never advance a decision. */
@Service
public class ChatTaskDeliveryReviewStore {
    private final JdbcTemplate jdbc;
    public ChatTaskDeliveryReviewStore(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    public record Operation(String operationId, String taskId, String key, String digest,
            String deliveryId, String state, long stateVersion, String receiptJson) { }

    @Transactional(rollbackFor = Exception.class)
    public Operation admit(Scope scope, String task, String key, Accept command) {
        var old = byKey(scope, task, key, false);
        String digest = ChatDeliveryReviewContract.requestDigest(command);
        if (old != null) { requireDigest(old, digest); return old; }
        String id = "review_" + ChatDeliveryReviewContract.sha(ChatDeliveryReviewContract.json(List.of(
                "hall-review-operation-v1", scope.tenantId(), scope.ownerJiacn(), scope.clientId(), task, key)));
        long now = System.currentTimeMillis();
        try {
            jdbc.update("""
                INSERT INTO chat_task_delivery_review_operation
                (tenant_id,owner_jiacn,client_id,operation_id,task_id,delivery_id,idempotency_key,
                 request_sha256,batch_sha256,request_json,decision,state,state_version,origin,
                 target_artifact_ids_json,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'ACCEPT','PROCESSING',1,'USER_ACCEPT','[]',?,?)
                """, scope.tenantId(), scope.ownerJiacn(), scope.clientId(), id, task, command.deliveryId(), key,
                    digest, command.batchSha256(), ChatDeliveryReviewContract.json(ChatDeliveryReviewContract.body(command)), now, now);
        } catch (org.springframework.dao.DuplicateKeyException race) {
            // A locking read sees the winner even under a repeatable-read admission snapshot.
            old = byKey(scope, task, key, true);
            if (old == null) throw new IdempotencyConflict();
            requireDigest(old, digest); return old;
        }
        return require(scope, task, id, false);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
    public Operation admitRevision(ChatDeliveryRevisionService.BoundRevision bound) {
        var parent=bound.parent().outcome();var owner=parent.scope();
        var scope=new Scope(owner.tenantId(),owner.clientId(),owner.ownerJiacn());
        String key=ChatDeliveryRevisionService.eventId(parent),digest=ChatDeliveryRevisionService.digest(bound);
        var prior=byKey(scope,parent.taskId(),key,true);
        if(prior!=null){requireDigest(prior,digest);return prior;}
        String id="review_"+ChatDeliveryReviewContract.sha(ChatDeliveryReviewContract.json(List.of(
                "hall-review-operation-v1",scope.tenantId(),scope.ownerJiacn(),scope.clientId(),parent.taskId(),key)));
        long now=System.currentTimeMillis();var revision=bound.parent().validated().interactionOutcome().revision();
        jdbc.update("""
            INSERT INTO chat_task_delivery_review_operation
             (tenant_id,owner_jiacn,client_id,operation_id,task_id,delivery_id,idempotency_key,request_sha256,
              batch_sha256,request_json,decision,state,state_version,origin,source_request_id,source_message_id,
              parent_outcome_id,parent_final_digest,target_artifact_ids_json,created_at,updated_at)
             VALUES (?,?,?,?,?,?,?,?,?,?,'REQUEST_CHANGES','PROCESSING',1,'MESSAGE_REVISION',?,?,?,?,?,?,?)
            """,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),id,parent.taskId(),bound.basis().deliveryId(),key,digest,
                bound.basis().batchSha256(),ChatDeliveryReviewContract.json(java.util.Map.of("basis",bound.basis(),"revision",revision)),
                parent.requestId(),Long.toString(bound.parent().admission().userMessageId()),parent.outcomeId(),parent.finalDigest(),
                ChatDeliveryReviewContract.json(revision.targetArtifactIds()),now,now);
        return require(scope,parent.taskId(),id,true);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
    public void bindRevisionChild(Scope scope,Operation operation,String child) {
        if(jdbc.update("""
            UPDATE chat_task_delivery_review_operation SET child_request_id=? WHERE BINARY tenant_id=BINARY ?
             AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY operation_id=BINARY ?
             AND state='PROCESSING' AND state_version=? AND origin='MESSAGE_REVISION' AND child_request_id IS NULL
            """,child,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),operation.operationId(),operation.stateVersion())!=1)throw new Conflict();
    }
    /** Terminal audit only after the owner-scoped root is unavailable. No task/delivery mutation.
     * Null domain projections explicitly mean unavailable, never guessed from the frozen basis. */
    @Transactional(rollbackFor=Exception.class)
    public void rejectUnavailableRevision(ChatDeliveryRevisionService.BoundRevision bound,
            ChatDeliberationOutboxService outbox,ChatDeliberationOutboxService.Claim claim,String code) {
        var row=bound.parent().outcome();var s=row.scope();var owner=new Scope(s.tenantId(),s.clientId(),s.ownerJiacn());
        var op=admitRevision(bound);
        if("PROCESSING".equals(op.state()))settle(owner,op,new Receipt(1,op.operationId(),row.taskId(),bound.basis().deliveryId(),
                "REQUEST_CHANGES","REJECTED",Long.toString(op.stateVersion()+1),null,null,null,null,
                null,row.requestId(),s.conversationId(),code,false));
        else committedReceipt(op);
        long now=System.currentTimeMillis();outbox.requireActiveClaim(claim,now);
        if(!outbox.sent(claim,now))throw new IllegalStateException("DELIVERY_REVISION_CLAIM_LOST");
    }
    public Operation byKey(Scope scope, String task, String key, boolean lock) {
        return one("idempotency_key", scope, task, key, lock);
    }
    public Operation require(Scope scope, String task, String operation, boolean lock) {
        var result = one("operation_id", scope, task, operation, lock);
        if (result == null) throw new Missing(); return result;
    }
    private Operation one(String field, Scope s, String task, String value, boolean lock) {
        List<Operation> rows = jdbc.query("""
            SELECT operation_id,task_id,idempotency_key,request_sha256,delivery_id,state,state_version,receipt_json
            FROM chat_task_delivery_review_operation WHERE BINARY tenant_id=BINARY ?
             AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY task_id=BINARY ? AND BINARY
            """ + field + "=BINARY ?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Operation(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getLong(7), r.getString(8)),
                s.tenantId(), s.ownerJiacn(), s.clientId(), task, value);
        if (rows.size() > 1) throw new Conflict(); return rows.isEmpty() ? null : rows.getFirst();
    }
    /** Caller holds the root lock and operation lock in the same transaction as decide(). */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY, rollbackFor = Exception.class)
    public Receipt settle(Scope s, Operation operation, Receipt receipt) {
        if (jdbc.update("""
                UPDATE chat_task_delivery_review_operation SET state=?,state_version=state_version+1,receipt_json=?,updated_at=?
                WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ?
                 AND BINARY task_id=BINARY ? AND BINARY operation_id=BINARY ? AND state='PROCESSING' AND state_version=?
                """, receipt.state(), ChatDeliveryReviewContract.json(receipt), System.currentTimeMillis(),
                s.tenantId(), s.ownerJiacn(), s.clientId(), operation.taskId(), operation.operationId(), operation.stateVersion()) != 1) throw new Conflict();
        return receipt;
    }
    @Transactional(readOnly = true)
    public Receipt progress(Scope s, Operation operation) {
        List<Receipt> rows = jdbc.query("""
            SELECT t.reward_status,t.task_version,d.state,d.version
            FROM agent_task_meta t JOIN agent_task_formal_delivery d
             ON BINARY d.tenant_id=BINARY t.tenant_id AND BINARY d.client_id=BINARY t.client_id
             AND BINARY d.task_id=BINARY t.task_id
            WHERE BINARY t.tenant_id=BINARY ? AND BINARY t.owner_jiacn=BINARY ? AND BINARY t.client_id=BINARY ?
             AND BINARY t.task_id=BINARY ? AND BINARY d.delivery_id=BINARY ?
            """, (r,n) -> new Receipt(1,operation.operationId(),operation.taskId(),operation.deliveryId(),"ACCEPT","PROCESSING",
                Long.toString(operation.stateVersion()),r.getString(1).toUpperCase(java.util.Locale.ROOT),Long.toString(r.getLong(2)),
                r.getString(3).toUpperCase(java.util.Locale.ROOT),Long.toString(r.getLong(4)),null,null,null,null,false),
                s.tenantId(),s.ownerJiacn(),s.clientId(),operation.taskId(),operation.deliveryId());
        if (rows.size()!=1) throw new Conflict(); return rows.getFirst();
    }
    public long assignmentRevision(Scope s, String task) {
        List<Long> rows = jdbc.query("""
            SELECT assignment_revision FROM agent_task_execution_grant WHERE BINARY tenant_id=BINARY ?
             AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY task_id=BINARY ? AND state='ACTIVE'
            """, (r, n) -> r.getLong(1), s.tenantId(), s.clientId(), s.ownerJiacn(), task);
        if (rows.size() != 1) throw new Conflict(); return rows.getFirst();
    }
    public static Receipt committedReceipt(Operation operation) {
        Receipt value=ChatDeliveryReviewContract.receipt(operation.receiptJson());
        if (!operation.operationId().equals(value.operationId()) || !operation.taskId().equals(value.taskId())
                || !operation.deliveryId().equals(value.deliveryId()) || !operation.state().equals(value.state())
                || !Long.toString(operation.stateVersion()).equals(value.stateVersion())) throw new Conflict();
        return value;
    }
    public static void requireDigest(Operation operation, String digest) {
        if (!operation.digest().equals(digest)) throw new IdempotencyConflict();
    }
}
