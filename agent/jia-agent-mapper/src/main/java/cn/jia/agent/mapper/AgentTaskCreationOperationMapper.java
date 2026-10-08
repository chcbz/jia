package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskCreationOperationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskCreationOperationMapper
        extends BaseMapper<AgentTaskCreationOperationEntity> {
    String COLUMNS = """
            id,operation_id,owner_jiacn,idempotency_key,request_hash,operation_state,
            task_id,requirement_revision,input_refs_json,created_at,completed_at,
            tenant_id,client_id,create_time,update_time
            """;
    String EXACT_SCOPE = """
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;
    String EXACT_KEY = """
              AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
            """;

    @Insert("""
            INSERT INTO agent_task_creation_operation
              (operation_id,owner_jiacn,idempotency_key,request_hash,operation_state,
               task_id,requirement_revision,input_refs_json,created_at,completed_at,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{operationId},#{ownerJiacn},#{idempotencyKey},#{requestHash},'PROCESSING',
               NULL,NULL,#{inputRefsJson},#{createdAt},NULL,
               #{tenantId},#{clientId},#{createdAt},#{createdAt})
            ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id),update_time=update_time
            """)
    int reserve(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("operationId") String operationId,
            @Param("inputRefsJson") String inputRefsJson,
            @Param("createdAt") long createdAt);

    @Select("SELECT " + COLUMNS + """
              FROM agent_task_creation_operation
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND idempotency_key=#{idempotencyKey}
            """ + EXACT_SCOPE + EXACT_KEY + """
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskCreationOperationEntity selectForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT " + COLUMNS + """
              FROM agent_task_creation_operation
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND idempotency_key=#{idempotencyKey}
            """ + EXACT_SCOPE + EXACT_KEY + """
             LIMIT 1
            """)
    AgentTaskCreationOperationEntity select(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE agent_task_creation_operation
               SET operation_state='COMMITTED',task_id=#{taskId},
                   requirement_revision=#{requirementRevision},completed_at=#{completedAt},
                   update_time=#{completedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND operation_id=#{operationId}
               AND request_hash=#{requestHash} AND input_refs_json=CAST(#{inputRefsJson} AS JSON)
               AND operation_state='PROCESSING'
            """ + EXACT_SCOPE + """
               AND CAST(operation_id AS BINARY)=CAST(#{operationId} AS BINARY)
               AND OCTET_LENGTH(operation_id)=OCTET_LENGTH(#{operationId})
               AND CAST(request_hash AS BINARY)=CAST(#{requestHash} AS BINARY)
               AND CAST(operation_state AS BINARY)=CAST('PROCESSING' AS BINARY)
               AND OCTET_LENGTH(operation_state)=OCTET_LENGTH('PROCESSING')
            """)
    int commit(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("operationId") String operationId,
            @Param("requestHash") String requestHash,
            @Param("inputRefsJson") String inputRefsJson,
            @Param("taskId") String taskId,
            @Param("requirementRevision") long requirementRevision,
            @Param("completedAt") long completedAt);
}
