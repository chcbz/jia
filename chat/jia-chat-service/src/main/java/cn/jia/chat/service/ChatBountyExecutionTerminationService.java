package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owner explicitly stops accepting a started but undelivered execution. This is NOT a retry,
 * Provider cancellation, refund, or proof that no off-platform copy exists. No generation authority
 * is created, consumed or removed. The existing durable journal is the atomic idempotency receipt.
 */
@Service
@ConditionalOnProperty(prefix="chat.bounty-media",name="enabled",havingValue="true")
public class ChatBountyExecutionTerminationService {
    public static final String REASON = "OWNER_ABANDONED_UNDELIVERED";
    // Execution FAILED means delivery did not complete, not that Provider generation was cancelled.
    // Keep its existing schema/API code; the immutable journal carries the specific owner decision.
    private static final String DELIVERY_FAILURE_CODE = "AGENT_DELIVERY_FAILED";
    public enum Reason { INVALID_REQUEST, NOT_FOUND_OR_FORBIDDEN, CONFLICT, UNAVAILABLE }
    public static final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason=reason; }
        public Reason reason() { return reason; }
    }
    public record Scope(String tenantId,String ownerJiacn,String clientId,String conversationId) { }
    public record Command(String stepId,String executionId,String expectedRequestStateVersion,
            String expectedStepStateVersion,String reason) { }
    public record Receipt(String operationId,String conversationId,String requestId,String stepId,
            String executionId,String state,String requestStateVersion,String stepStateVersion,
            String reason,boolean providerAlreadyStarted,boolean providerStopped,boolean paidFactsPreserved) { }
    public record Audit(String type,String conversationId,String requestId,String stepId,
            String commandDigest,Receipt receipt) { }
    private record Snapshot(String requestId,long requestRevision,long requestVersion,String requestState,
            String conversationId,long generation,String stepId,String kind,String stepState,long stepVersion,
            String taskId,String targetAgentId,long assignmentRevision,String grantId,long grantVersion,
            String inputDigest,String intentId,String executionId,String linkState,long linkVersion) { }
    private record Execution(String executionId,String taskId,String runId,String conversationId,
            String targetAgentId,String mode,String state,String grantId,long grantVersion,long assignmentRevision,
            String idempotencyKey,Long providerStartedAt,Long providerLeaseVersion) { }

    private final JdbcTemplate jdbc;
    private final AgentTaskMutationTransaction roots;
    private final ChatConversationDao conversations;
    private final ChatDeliberationDao events;
    private final ChatConversationEventBroker broker;
    private final WorkspaceConversationAccessService access;
    private final ObjectMapper json;
    public ChatBountyExecutionTerminationService(JdbcTemplate jdbc,AgentTaskMutationTransaction roots,
            ChatConversationDao conversations,ChatDeliberationDao events,ChatConversationEventBroker broker,
            WorkspaceConversationAccessService access,ObjectMapper json) {
        this.jdbc=Objects.requireNonNull(jdbc);this.roots=Objects.requireNonNull(roots);
        this.conversations=Objects.requireNonNull(conversations);this.events=Objects.requireNonNull(events);
        this.broker=Objects.requireNonNull(broker);this.access=Objects.requireNonNull(access);this.json=Objects.requireNonNull(json);
    }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Receipt abandon(Scope scope,String requestId,String key,Command command) {
        validate(scope,requestId,key);validate(command);
        Snapshot hint=snapshot(scope,requestId,false);
        if(hint==null)throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
        String eventId=operationId(scope,key),digest=digest(requestId,command);
        // Same order as runtime stage/commit and result projection: task root -> execution -> Chat.
        // No grant/model/provider re-admission is needed to stop accepting an owned result.
        return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),hint.taskId(),root->{
            requireRoot(root,scope,hint);
            Execution execution=execution(scope,hint.executionId());
            ChatConversationEntity live=conversations.lockScopedById(scope.ownerJiacn(),scope.clientId(),scope.conversationId());
            requireConversation(live,scope,hint);
            Snapshot row=snapshot(scope,requestId,true);
            requireSameBinding(hint,row);
            requireRoot(root,scope,row);
            requireAccess(scope,row);
            requireBinding(row,execution,command);
            Audit prior=audit(scope,row,eventId);
            if(prior!=null){
                if(!Objects.equals(prior.commandDigest(),digest)||!requestId.equals(prior.requestId()))
                    throw fail(Reason.CONFLICT);
                requireReceiptBinding(row,prior);
                return prior.receipt();
            }
            if(!"RUNNING".equals(row.requestState())||!"RUNNING".equals(row.stepState())
                    ||!"RUNNING".equals(row.linkState())
                    ||row.requestVersion()!=decimal(command.expectedRequestStateVersion())
                    ||row.stepVersion()!=decimal(command.expectedStepStateVersion()))throw fail(Reason.CONFLICT);
            if(!List.of("QUEUED","FAILED").contains(execution.state())
                    ||execution.providerStartedAt()==null||execution.providerLeaseVersion()==null)
                throw fail(Reason.CONFLICT);
            // Refuse to abandon bytes which already reached the platform, even if their ACK was lost.
            // Every writer of these rows already holds the same task root/execution lock.
            if(!jdbc.queryForList("""
                    SELECT output_id FROM agent_personal_workspace_execution_output
                    WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ?
                      AND BINARY owner_jiacn=BINARY ? AND BINARY execution_id=BINARY ? FOR UPDATE
                    """,String.class,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),row.executionId()).isEmpty())
                throw fail(Reason.CONFLICT);
            long nextRequest=nextVersion(row.requestVersion()),nextStep=nextVersion(row.stepVersion());
            nextVersion(row.linkVersion());
            long now=System.currentTimeMillis();
            if("QUEUED".equals(execution.state())&&jdbc.update("""
                    UPDATE agent_personal_workspace_execution SET execution_state='FAILED',failure_code=?,
                      failure_message='Owner stopped accepting this undelivered result; Provider execution is not undone',failed_at=?
                    WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ?
                      AND BINARY execution_id=BINARY ? AND execution_state='QUEUED'
                      AND conversation_provider_started_at=? AND conversation_provider_lease_version=?
                    """,DELIVERY_FAILURE_CODE,now,scope.tenantId(),scope.clientId(),scope.ownerJiacn(),row.executionId(),
                    execution.providerStartedAt(),execution.providerLeaseVersion())!=1)throw fail(Reason.CONFLICT);
            if(jdbc.update("""
                    UPDATE chat_interaction_step SET state='CANCELLED',state_version=state_version+1,updated_at=?
                    WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ?
                      AND BINARY step_id=BINARY ? AND state='RUNNING' AND state_version=?
                    """,now,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.stepId(),row.stepVersion())!=1
                ||jdbc.update("""
                    UPDATE chat_step_execution_link SET state='CANCELLED',state_version=state_version+1,updated_at=?
                    WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ?
                      AND BINARY step_id=BINARY ? AND BINARY execution_id=BINARY ? AND state='RUNNING' AND state_version=?
                    """,now,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.stepId(),row.executionId(),row.linkVersion())!=1
                ||jdbc.update("""
                    UPDATE chat_request SET aggregate_state='CANCELLED',state_version=state_version+1,updated_at=?
                    WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ?
                      AND BINARY request_id=BINARY ? AND request_revision=? AND aggregate_state='RUNNING' AND state_version=?
                    """,now,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId,row.requestRevision(),row.requestVersion())!=1)
                throw fail(Reason.CONFLICT);
            Receipt receipt=new Receipt(eventId,scope.conversationId(),requestId,row.stepId(),row.executionId(),
                    "CANCELLED",Long.toString(nextRequest),Long.toString(nextStep),REASON,true,false,true);
            Audit audit=new Audit("execution_abandoned",scope.conversationId(),requestId,row.stepId(),digest,receipt);
            ChatConversationEventEntity event=new ChatConversationEventEntity().setEventId(eventId)
                    .setTenantId(scope.tenantId()).setOwnerJiacn(scope.ownerJiacn()).setClientId(scope.clientId())
                    .setConversationId(scope.conversationId()).setConversationGeneration(row.generation())
                    .setRequestId(requestId).setEventType("execution_abandoned").setEventVersion(0L)
                    .setPayloadJson(JsonUtil.toJson(audit)).setOccurredAt(now);
            if(events.insertEvent(event)!=1||event.getEventSequence()==null
                    ||events.assignEventVersion(event.getEventSequence())!=1)throw fail(Reason.UNAVAILABLE);
            publishAfterCommit(scope,row,event,receipt);
            return receipt;
        });
    }

    /** Resolves an unknown POST outcome without replaying any execution or save. */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Receipt get(Scope scope,String requestId,String key) {
        validate(scope,requestId,key);
        Snapshot hint=snapshot(scope,requestId,false);
        if(hint==null)throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
        return roots.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                hint.taskId(),root->{
            requireRoot(root,scope,hint);
            Execution execution=execution(scope,hint.executionId());
            requireConversation(conversations.lockScopedById(scope.ownerJiacn(),scope.clientId(),scope.conversationId()),scope,hint);
            Snapshot row=snapshot(scope,requestId,true);
            requireSameBinding(hint,row);
            requireAccess(scope,row);
            requireBinding(row,execution,new Command(row.stepId(),row.executionId(),"0","0",REASON));
            Audit prior=audit(scope,row,operationId(scope,key));
            if(prior==null||!requestId.equals(prior.requestId()))throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
            requireReceiptBinding(row,prior);
            return prior.receipt();
        });
    }

    private Snapshot snapshot(Scope s,String requestId,boolean lock) {
        var rows=jdbc.query("""
                SELECT r.request_id,r.request_revision,r.state_version,r.aggregate_state,r.conversation_id,
                  r.conversation_generation,s.step_id,s.kind,s.state,s.state_version,s.task_id,s.target_agent_id,
                  s.assignment_revision,s.grant_id,s.grant_version,s.input_snapshot_digest,l.execution_intent_id,
                  l.execution_id,l.state,l.state_version
                FROM chat_request r JOIN chat_interaction_step s ON BINARY s.tenant_id=BINARY r.tenant_id
                  AND BINARY s.owner_jiacn=BINARY r.owner_jiacn AND BINARY s.client_id=BINARY r.client_id
                  AND BINARY s.request_id=BINARY r.request_id AND s.request_revision=r.request_revision
                  AND BINARY s.conversation_id=BINARY r.conversation_id AND s.conversation_generation=r.conversation_generation
                JOIN chat_step_execution_link l ON BINARY l.tenant_id=BINARY s.tenant_id
                  AND BINARY l.owner_jiacn=BINARY s.owner_jiacn AND BINARY l.client_id=BINARY s.client_id
                  AND BINARY l.step_id=BINARY s.step_id
                WHERE BINARY r.tenant_id=BINARY ? AND BINARY r.owner_jiacn=BINARY ? AND BINARY r.client_id=BINARY ?
                  AND BINARY r.conversation_id=BINARY ? AND BINARY r.request_id=BINARY ?
                """+(lock?" FOR UPDATE":""),(rs,n)->new Snapshot(rs.getString(1),rs.getLong(2),rs.getLong(3),
                rs.getString(4),rs.getString(5),rs.getLong(6),rs.getString(7),rs.getString(8),rs.getString(9),
                rs.getLong(10),rs.getString(11),rs.getString(12),rs.getLong(13),rs.getString(14),rs.getLong(15),
                rs.getString(16),rs.getString(17),rs.getString(18),rs.getString(19),rs.getLong(20)),
                s.tenantId(),s.ownerJiacn(),s.clientId(),s.conversationId(),requestId);
        if(rows.size()!=1||!"EXECUTE".equals(rows.getFirst().kind())||rows.getFirst().executionId()==null)return null;
        return rows.getFirst();
    }
    private Execution execution(Scope s,String id) {
        var rows=jdbc.query("""
                SELECT execution_id,task_id,run_id,conversation_id,target_agent_id,execution_mode,execution_state,
                  task_grant_id,task_grant_version,assignment_revision,idempotency_key,
                  conversation_provider_started_at,conversation_provider_lease_version
                FROM agent_personal_workspace_execution WHERE BINARY tenant_id=BINARY ?
                  AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY execution_id=BINARY ? FOR UPDATE
                """,(rs,n)->new Execution(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(6),rs.getString(7),rs.getString(8),rs.getLong(9),rs.getLong(10),rs.getString(11),
                rs.getObject(12,Long.class),rs.getObject(13,Long.class)),s.tenantId(),s.ownerJiacn(),s.clientId(),id);
        if(rows.size()!=1)throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);return rows.getFirst();
    }
    private Audit audit(Scope s,Snapshot row,String id) {
        var rows=jdbc.queryForList("""
                SELECT payload_json FROM chat_conversation_event WHERE BINARY tenant_id=BINARY ?
                  AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY conversation_id=BINARY ?
                  AND BINARY event_id=BINARY ? AND conversation_generation=? AND event_type='execution_abandoned' FOR UPDATE
                """,String.class,s.tenantId(),s.ownerJiacn(),s.clientId(),s.conversationId(),id,row.generation());
        if(rows.isEmpty())return null;if(rows.size()!=1)throw fail(Reason.UNAVAILABLE);
        try { Audit a=json.readValue(rows.getFirst(),Audit.class);
            if(a.receipt()==null||!id.equals(a.receipt().operationId())||!s.conversationId().equals(a.conversationId())
                    ||!"execution_abandoned".equals(a.type())||!REASON.equals(a.receipt().reason())
                    ||!s.conversationId().equals(a.receipt().conversationId())
                    ||!Objects.equals(a.requestId(),a.receipt().requestId())
                    ||!Objects.equals(a.stepId(),a.receipt().stepId())
                    ||!"CANCELLED".equals(a.receipt().state())||!a.receipt().providerAlreadyStarted()
                    ||a.receipt().providerStopped()||!a.receipt().paidFactsPreserved()
                    ||a.commandDigest()==null||!a.commandDigest().matches("[0-9a-f]{64}"))throw fail(Reason.UNAVAILABLE);
            return a;
        }catch(Failure failure){throw failure;}catch(Exception malformed){throw fail(Reason.UNAVAILABLE);}
    }
    private void publishAfterCommit(Scope scope,Snapshot row,ChatConversationEventEntity event,Receipt receipt) {
        if(!TransactionSynchronizationManager.isSynchronizationActive())throw fail(Reason.UNAVAILABLE);
        Map<String,Object> frame=new LinkedHashMap<>();frame.put("type","execution_abandoned");
        frame.put("conversationId",scope.conversationId());frame.put("requestId",row.requestId());
        frame.put("stepId",row.stepId());frame.put("receipt",receipt);frame.put("eventId",event.getEventId());
        frame.put("eventSequence",Long.toString(event.getEventSequence()));frame.put("eventVersion",Long.toString(event.getEventSequence()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){broker.publishIfSubscribed(scope.conversationId(),row.generation(),()->{
                var current=conversations.findScopedById(scope.ownerJiacn(),scope.clientId(),scope.conversationId());
                return current!=null&&current.getDeletedAt()==null&&Objects.equals(row.generation(),current.getLifecycleGeneration())
                        &&scope.tenantId().equals(current.getTenantId());
            },frame);}
        });
    }
    private void requireAccess(Scope scope,Snapshot row) {
        try {
            var current=access.requireAccessible(new WorkspaceConversationAccessService.Scope(
                    scope.tenantId(),scope.clientId(),scope.ownerJiacn()),scope.conversationId());
            if(current==null||!scope.conversationId().equals(current.conversationId())
                    ||!"bounty".equals(current.scopeType())||!row.taskId().equals(current.taskId())
                    ||!("task:"+row.taskId()).equals(current.scopeKey())
                    ||row.generation()!=current.lifecycleGeneration()
                    ||!current.targetAgentIds().contains(row.targetAgentId()))throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
        } catch(RuntimeException denied){throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);}
    }
    // States/versions can legitimately change while waiting for the task root (same-key replay).
    // Immutable bindings cannot. Never compare an unlocked whole-row snapshot for replay admission.
    private static void requireSameBinding(Snapshot a,Snapshot b) {
        if(b==null||!Objects.equals(a.requestId(),b.requestId())||a.requestRevision()!=b.requestRevision()
                ||!Objects.equals(a.conversationId(),b.conversationId())||a.generation()!=b.generation()
                ||!Objects.equals(a.stepId(),b.stepId())||!Objects.equals(a.taskId(),b.taskId())
                ||!Objects.equals(a.targetAgentId(),b.targetAgentId())||a.assignmentRevision()!=b.assignmentRevision()
                ||!Objects.equals(a.grantId(),b.grantId())||a.grantVersion()!=b.grantVersion()
                ||!Objects.equals(a.inputDigest(),b.inputDigest())||!Objects.equals(a.intentId(),b.intentId())
                ||!Objects.equals(a.executionId(),b.executionId()))throw fail(Reason.CONFLICT);
    }
    private static void requireReceiptBinding(Snapshot row,Audit prior) {
        Receipt r=prior.receipt();
        if(!Objects.equals(row.requestId(),prior.requestId())||!Objects.equals(row.stepId(),prior.stepId())
                ||!Objects.equals(row.executionId(),r.executionId())||!"CANCELLED".equals(row.requestState())
                ||!"CANCELLED".equals(row.stepState())||!"CANCELLED".equals(row.linkState())
                ||!Long.toString(row.requestVersion()).equals(r.requestStateVersion())
                ||!Long.toString(row.stepVersion()).equals(r.stepStateVersion()))throw fail(Reason.UNAVAILABLE);
    }
    private static long nextVersion(long value) {
        if(value<0||value==Long.MAX_VALUE)throw fail(Reason.CONFLICT);
        return value+1;
    }
    private static void requireBinding(Snapshot r,Execution e,Command c) {
        if(!c.stepId().equals(r.stepId())||!c.executionId().equals(r.executionId())||!r.executionId().equals(e.executionId())
                ||!r.taskId().equals(e.taskId())||!r.conversationId().equals(e.conversationId())
                ||!r.targetAgentId().equals(e.targetAgentId())||!"CONVERSATION".equals(e.mode())
                ||!r.grantId().equals(e.grantId())||r.grantVersion()!=e.grantVersion()
                ||r.assignmentRevision()!=e.assignmentRevision()||!Objects.equals("conv_"+sha(r.intentId()),e.idempotencyKey()))
            throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
    }
    private static void requireRoot(AgentTaskMetaEntity root,Scope scope,Snapshot row) {
        if(root==null||!scope.tenantId().equals(root.getTenantId())||!scope.ownerJiacn().equals(root.getOwnerJiacn())
                ||!scope.clientId().equals(root.getClientId())||!row.taskId().equals(root.getTaskId())
                ||!row.targetAgentId().equals(root.getAssignedAgentId())||root.getTaskVersion()==null
                ||root.getTaskVersion()<row.assignmentRevision())throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
    }
    private static void requireConversation(ChatConversationEntity c,Scope scope,Snapshot row) {
        if(c==null||c.getId()==null||!scope.conversationId().equals(c.getId().toString())||c.getDeletedAt()!=null
                ||!scope.tenantId().equals(c.getTenantId())||!scope.ownerJiacn().equals(c.getJiacn())
                ||!scope.clientId().equals(c.getClientId())||!Objects.equals(row.generation(),c.getLifecycleGeneration())
                ||!"juyiting".equals(c.getConversationType())
                ||!row.taskId().equals(c.getTaskId())||!"bounty".equals(c.getConversationScopeType())
                ||!("task:"+row.taskId()).equals(c.getConversationScopeKey()))throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
    }
    private static void validate(Scope s,String requestId,String key) {
        if(s==null)throw fail(Reason.INVALID_REQUEST);
        for(String value:List.of(Objects.toString(s.tenantId(),""),Objects.toString(s.ownerJiacn(),""),Objects.toString(s.clientId(),"")))
            if(!exact(value,50))throw fail(Reason.INVALID_REQUEST);
        if(!exact(requestId,100)||!exact(s.conversationId(),100)||key==null
                ||!key.matches("[A-Za-z0-9._~:/+\\-]{8,100}"))throw fail(Reason.INVALID_REQUEST);
    }
    private static void validate(Command c) {
        if(c==null||!exact(c.stepId(),64)||!exact(c.executionId(),64)||!REASON.equals(c.reason()))throw fail(Reason.INVALID_REQUEST);
        decimal(c.expectedRequestStateVersion());decimal(c.expectedStepStateVersion());
    }
    private static long decimal(String s) {
        if(s==null||!s.matches("0|[1-9][0-9]{0,18}"))throw fail(Reason.INVALID_REQUEST);
        try{return Long.parseLong(s);}catch(NumberFormatException invalid){throw fail(Reason.INVALID_REQUEST);}
    }
    private static boolean exact(String value,int max){return value!=null&&!value.isBlank()&&value.equals(value.strip())
            &&value.codePointCount(0,value.length())<=max&&value.codePoints().noneMatch(Character::isISOControl);}
    private static String operationId(Scope s,String key){return sha(CanonicalContextJson.write(Map.of("v",1,"tenant",s.tenantId(),
            "owner",s.ownerJiacn(),"client",s.clientId(),"conversation",s.conversationId(),"key",key,"operation","abandon-execution")));}
    private static String digest(String requestId,Command c){return sha(CanonicalContextJson.write(Map.of("requestId",requestId,
            "stepId",c.stepId(),"executionId",c.executionId(),"requestVersion",c.expectedRequestStateVersion(),
            "stepVersion",c.expectedStepStateVersion(),"reason",c.reason())));}
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static Failure fail(Reason reason){return new Failure(reason);}
}
