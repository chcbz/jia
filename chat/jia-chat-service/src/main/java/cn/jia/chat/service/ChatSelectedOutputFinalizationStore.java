package cn.jia.chat.service;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Named
public class ChatSelectedOutputFinalizationStore {
    private final JdbcTemplate jdbc;
    public ChatSelectedOutputFinalizationStore(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}

    public record Selection(String requestId,String stepId,String outputId,String sha256,String title,String purpose) { }
    public record Operation(String operationId,String taskId,String key,String digest,String conversationId,
            long expectedTaskVersion,long expectedAssignmentRevision,String summary,String state,String stage,
            long stateVersion,String deliveryId,String deliveryState,String taskState,long taskVersion,
            String errorCode,boolean retryable,List<Selection> selections) { }
    public record TaskFact(String state,long version) { }
    public record SourceFact(long generation,String executionId,String runId,String mime,String sha256,long byteLength) { }

    @Transactional(rollbackFor=Exception.class)
    public Operation create(String tenant,String owner,String client,String operationId,String taskId,String key,
            String digest,String conversationId,long expectedTaskVersion,long expectedAssignmentRevision,
            String summary,List<Selection> selections) {
        Operation existing=findByKey(tenant,owner,client,taskId,key);
        if(existing!=null){if(!digest.equals(existing.digest()))throw new Conflict();return existing;}
        TaskFact task=task(tenant,owner,client,taskId);
        long now=System.currentTimeMillis();
        try {
            if(jdbc.update("""
                INSERT INTO chat_selected_output_finalization
                (operation_id,tenant_id,owner_jiacn,client_id,task_id,idempotency_key,request_digest,
                 conversation_id,expected_task_version,expected_assignment_revision,summary,state,stage,
                 state_version,task_state,task_version,retryable,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'pending','PROMOTING',1,?,?,0,?,?)
                """,operationId,tenant,owner,client,taskId,key,digest,conversationId,expectedTaskVersion,
                    expectedAssignmentRevision,summary,task.state(),task.version(),now,now)!=1)throw new Persistence();
            for(int i=0;i<selections.size();i++){Selection s=selections.get(i);
                if(jdbc.update("""
                    INSERT INTO chat_selected_output_finalization_item
                    (tenant_id,owner_jiacn,client_id,operation_id,item_order,request_id,step_id,output_id,sha256,title,purpose)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,tenant,owner,client,operationId,i,s.requestId(),s.stepId(),s.outputId(),s.sha256(),s.title(),s.purpose())!=1)throw new Persistence();}
        } catch(org.springframework.dao.DuplicateKeyException race){
            Operation replay=findByKey(tenant,owner,client,taskId,key);
            if(replay==null||!digest.equals(replay.digest()))throw new Conflict();return replay;
        }
        return requireByOperation(tenant,owner,client,taskId,operationId);
    }

    @Transactional(rollbackFor=Exception.class)
    public Operation advance(String tenant,String owner,String client,Operation expected,String state,String stage,
            String deliveryId,String deliveryState,String taskState,long taskVersion,String errorCode,boolean retryable){
        int n=jdbc.update("""
            UPDATE chat_selected_output_finalization SET state=?,stage=?,state_version=state_version+1,
             delivery_id=?,delivery_state=?,task_state=?,task_version=?,error_code=?,retryable=?,updated_at=?
            WHERE BINARY tenant_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ?
             AND BINARY task_id=BINARY ? AND BINARY operation_id=BINARY ? AND state_version=?
            """,state,stage,deliveryId,deliveryState,taskState,taskVersion,errorCode,retryable,
                System.currentTimeMillis(),tenant,owner,client,expected.taskId(),expected.operationId(),expected.stateVersion());
        if(n==0){Operation concurrent=requireByOperation(tenant,owner,client,expected.taskId(),expected.operationId());
            if(concurrent.stateVersion()>expected.stateVersion())return concurrent;throw new Conflict();}
        if(n!=1)throw new Persistence();return requireByOperation(tenant,owner,client,expected.taskId(),expected.operationId());
    }

    @Transactional(readOnly=true)
    public Operation findByKey(String tenant,String owner,String client,String taskId,String key){
        List<Operation> rows=rows("""
            WHERE BINARY o.tenant_id=BINARY ? AND BINARY o.owner_jiacn=BINARY ? AND BINARY o.client_id=BINARY ?
              AND BINARY o.task_id=BINARY ? AND BINARY o.idempotency_key=BINARY ?
            """,tenant,owner,client,taskId,key);return one(rows);
    }
    @Transactional(readOnly=true)
    public Operation findByOperation(String tenant,String owner,String client,String taskId,String operationId){
        List<Operation> rows=rows("""
            WHERE BINARY o.tenant_id=BINARY ? AND BINARY o.owner_jiacn=BINARY ? AND BINARY o.client_id=BINARY ?
              AND BINARY o.task_id=BINARY ? AND BINARY o.operation_id=BINARY ?
            """,tenant,owner,client,taskId,operationId);return one(rows);
    }
    public Operation requireByOperation(String tenant,String owner,String client,String taskId,String operationId){
        Operation row=findByOperation(tenant,owner,client,taskId,operationId);if(row==null)throw new Missing();return row;
    }
    @Transactional(readOnly=true)
    public TaskFact task(String tenant,String owner,String client,String taskId){
        List<TaskFact> rows=jdbc.query("""
            SELECT reward_status,task_version FROM agent_task_meta WHERE BINARY tenant_id=BINARY ?
             AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY task_id=BINARY ?
            """,(rs,n)->new TaskFact(rs.getString(1),rs.getLong(2)),tenant,owner,client,taskId);
        if(rows.size()!=1)throw new Missing();return rows.getFirst();
    }
    @Transactional(readOnly=true)
    public SourceFact source(String tenant,String owner,String client,String conversationId,
            long generation,String requestId,String stepId,String outputId){
        List<SourceFact> rows=jdbc.query("""
            SELECT a.conversation_generation,a.execution_id,a.run_id,a.content_mime_type,a.sha256,a.byte_length
            FROM chat_conversation_asset a JOIN chat_conversation c ON c.id=CAST(a.conversation_id AS UNSIGNED)
             AND BINARY CAST(c.id AS CHAR)=BINARY a.conversation_id AND c.deleted_at IS NULL
             AND c.lifecycle_generation=a.conversation_generation AND BINARY c.tenant_id=BINARY a.tenant_id
             AND BINARY c.jiacn=BINARY a.owner_jiacn AND BINARY c.client_id=BINARY a.client_id
            WHERE BINARY a.tenant_id=BINARY ? AND BINARY a.owner_jiacn=BINARY ? AND BINARY a.client_id=BINARY ?
             AND BINARY a.conversation_id=BINARY ? AND a.conversation_generation=?
             AND BINARY a.request_id=BINARY ? AND BINARY a.step_id=BINARY ? AND BINARY a.output_id=BINARY ?
            """,(rs,n)->new SourceFact(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)),
                tenant,owner,client,conversationId,generation,requestId,stepId,outputId);
        return rows.size()==1?rows.getFirst():null;
    }

    private List<Operation> rows(String where,Object...args){
        List<Operation> base=jdbc.query("""
            SELECT o.operation_id,o.task_id,o.idempotency_key,o.request_digest,o.conversation_id,
             o.expected_task_version,o.expected_assignment_revision,o.summary,o.state,o.stage,o.state_version,
             o.delivery_id,o.delivery_state,o.task_state,o.task_version,o.error_code,o.retryable
            FROM chat_selected_output_finalization o
            """+where,(rs,n)->new Operation(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getLong(6),rs.getLong(7),rs.getString(8),rs.getString(9),rs.getString(10),
                rs.getLong(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getLong(15),rs.getString(16),
                rs.getBoolean(17),List.of()),args);
        if(base.isEmpty())return base;
        if(base.size()!=1)throw new Persistence();
        Operation row=base.getFirst();
        List<Selection> selections=items(row.operationId(),args);
        if(selections.isEmpty()||selections.size()>99)throw new Persistence();
        return List.of(new Operation(row.operationId(),row.taskId(),row.key(),row.digest(),row.conversationId(),
                row.expectedTaskVersion(),row.expectedAssignmentRevision(),row.summary(),row.state(),row.stage(),
                row.stateVersion(),row.deliveryId(),row.deliveryState(),row.taskState(),row.taskVersion(),
                row.errorCode(),row.retryable(),selections));
    }
    private List<Selection> items(String operationId,Object[] scopeArgs){
        String tenant=(String)scopeArgs[0],owner=(String)scopeArgs[1],client=(String)scopeArgs[2];
        record Ordered(int order,Selection selection) { }
        List<Ordered> rows=jdbc.query("""
            SELECT item_order,request_id,step_id,output_id,sha256,title,purpose
            FROM chat_selected_output_finalization_item WHERE BINARY tenant_id=BINARY ?
             AND BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND BINARY operation_id=BINARY ?
            ORDER BY item_order
            """,(rs,n)->new Ordered(rs.getInt(1),new Selection(rs.getString(2),rs.getString(3),rs.getString(4),
                    rs.getString(5),rs.getString(6),rs.getString(7))),tenant,owner,client,operationId);
        for(int index=0;index<rows.size();index++)if(rows.get(index).order()!=index)throw new Persistence();
        return rows.stream().map(Ordered::selection).toList();
    }
    private static Operation one(List<Operation> rows){if(rows.size()>1)throw new Persistence();return rows.isEmpty()?null:rows.getFirst();}
    public static final class Missing extends RuntimeException { }
    public static final class Conflict extends RuntimeException { }
    public static final class Persistence extends RuntimeException { }
}
