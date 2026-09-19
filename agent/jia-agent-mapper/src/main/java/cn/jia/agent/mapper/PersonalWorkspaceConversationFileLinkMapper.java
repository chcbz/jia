package cn.jia.agent.mapper;

import cn.jia.agent.dao.PersonalWorkspaceConversationLinkDao.OperationRow;
import cn.jia.agent.entity.PersonalWorkspaceConversationFileLinkEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface PersonalWorkspaceConversationFileLinkMapper
        extends BaseMapper<PersonalWorkspaceConversationFileLinkEntity> {
    String EXACT_SCOPE = """
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;
    String EXACT_CONVERSATION = """
              AND CAST(conversation_id AS BINARY)=CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id)=OCTET_LENGTH(#{conversationId})
            """;
    String EXACT_FILE = """
              AND CAST(file_id AS BINARY)=CAST(#{fileId} AS BINARY)
              AND OCTET_LENGTH(file_id)=OCTET_LENGTH(#{fileId})
            """;
    String LINK_COLUMNS = """
            id, relation_id, owner_jiacn, conversation_id, file_id, file_version, link_role,
            link_state, relation_revision, created_at, detached_at,
            tenant_id, client_id, create_time, update_time
            """;

    @Select("""
            SELECT file_id FROM agent_personal_workspace_file
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND file_id=#{fileId}
            """ + EXACT_SCOPE + EXACT_FILE + """
             LIMIT 1 FOR UPDATE
            """)
    String selectFileForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId);

    @Select("""
            SELECT file_id FROM agent_personal_workspace_file
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND file_id=#{fileId}
               AND state='ACTIVE'
               AND CAST(state AS BINARY)=CAST('ACTIVE' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('ACTIVE')
            """ + EXACT_SCOPE + EXACT_FILE + """
             LIMIT 1 FOR UPDATE
            """)
    String selectActiveFileForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId);

    @Select("""
            SELECT version FROM agent_personal_workspace_file_version
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND file_id=#{fileId} AND version=#{version}
            """ + EXACT_SCOPE + EXACT_FILE + """
             LIMIT 1
            """)
    Integer selectVersion(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId, @Param("version") int version);

    @Select("SELECT " + LINK_COLUMNS + """
              FROM agent_personal_workspace_conversation_file_link
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND conversation_id=#{conversationId}
               AND relation_id=#{relationId}
            """ + EXACT_SCOPE + EXACT_CONVERSATION + """
               AND CAST(relation_id AS BINARY)=CAST(#{relationId} AS BINARY)
               AND OCTET_LENGTH(relation_id)=OCTET_LENGTH(#{relationId})
             LIMIT 1
            """)
    PersonalWorkspaceConversationFileLinkEntity selectByRelation(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId, @Param("relationId") String relationId);

    @Select("SELECT " + LINK_COLUMNS + """
              FROM agent_personal_workspace_conversation_file_link
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND conversation_id=#{conversationId}
               AND relation_id=#{relationId}
            """ + EXACT_SCOPE + EXACT_CONVERSATION + """
               AND CAST(relation_id AS BINARY)=CAST(#{relationId} AS BINARY)
               AND OCTET_LENGTH(relation_id)=OCTET_LENGTH(#{relationId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceConversationFileLinkEntity selectByRelationForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("conversationId") String conversationId,
            @Param("relationId") String relationId);

    @Select("SELECT " + LINK_COLUMNS + """
              FROM agent_personal_workspace_conversation_file_link
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND conversation_id=#{conversationId}
               AND file_id=#{fileId} AND file_version=#{version} AND link_role=#{role}
            """ + EXACT_SCOPE + EXACT_CONVERSATION + EXACT_FILE + """
               AND CAST(link_role AS BINARY)=CAST(#{role} AS BINARY)
               AND OCTET_LENGTH(link_role)=OCTET_LENGTH(#{role})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceConversationFileLinkEntity selectBySelectionForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("conversationId") String conversationId,
            @Param("fileId") String fileId, @Param("version") int version,
            @Param("role") String role);

    @Select("<script>SELECT " + LINK_COLUMNS + """
              FROM agent_personal_workspace_conversation_file_link
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND conversation_id=#{conversationId}
            """ + EXACT_SCOPE + EXACT_CONVERSATION + """
            <if test='beforeCreatedAt != null and afterRelationId != null'>
              AND (created_at &lt; #{beforeCreatedAt}
                   OR (created_at=#{beforeCreatedAt}
                       AND CAST(relation_id AS BINARY) &gt; CAST(#{afterRelationId} AS BINARY)))
            </if>
             ORDER BY created_at DESC, CAST(relation_id AS BINARY) ASC, id ASC
             LIMIT #{limit}
            </script>
            """)
    List<PersonalWorkspaceConversationFileLinkEntity> selectLinks(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId, @Param("beforeCreatedAt") Long beforeCreatedAt,
            @Param("afterRelationId") String afterRelationId, @Param("limit") int limit);

    @Select("SELECT " + LINK_COLUMNS + """
              FROM agent_personal_workspace_conversation_file_link
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND file_id=#{fileId}
               AND link_state='ACTIVE'
            """ + EXACT_SCOPE + EXACT_FILE + """
               AND CAST(link_state AS BINARY)=CAST('ACTIVE' AS BINARY)
               AND OCTET_LENGTH(link_state)=OCTET_LENGTH('ACTIVE')
             ORDER BY created_at DESC, CAST(relation_id AS BINARY) ASC, id ASC
             LIMIT #{limit}
            """)
    List<PersonalWorkspaceConversationFileLinkEntity> selectActiveByFile(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("fileId") String fileId,
            @Param("limit") int limit);

    @Update("""
            UPDATE agent_personal_workspace_file
               SET metadata_revision=metadata_revision+1, update_time=#{now}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND file_id=#{fileId}
            """ + EXACT_SCOPE + EXACT_FILE)
    int bumpFileMetadataRevision(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId, @Param("now") long now);

    @Update("""
            UPDATE agent_personal_workspace_conversation_file_link
               SET link_state=#{nextState}, relation_revision=#{nextRevision},
                   detached_at=#{detachedAt}, update_time=#{now}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND conversation_id=#{conversationId}
               AND relation_id=#{relationId} AND link_state=#{expectedState}
               AND relation_revision=#{expectedRevision}
            """ + EXACT_SCOPE + EXACT_CONVERSATION + """
               AND CAST(relation_id AS BINARY)=CAST(#{relationId} AS BINARY)
               AND OCTET_LENGTH(relation_id)=OCTET_LENGTH(#{relationId})
               AND CAST(link_state AS BINARY)=CAST(#{expectedState} AS BINARY)
               AND OCTET_LENGTH(link_state)=OCTET_LENGTH(#{expectedState})
            """)
    int updateState(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("conversationId") String conversationId,
            @Param("relationId") String relationId, @Param("expectedState") String expectedState,
            @Param("expectedRevision") long expectedRevision, @Param("nextState") String nextState,
            @Param("nextRevision") long nextRevision, @Param("detachedAt") Long detachedAt,
            @Param("now") long now);

    @Insert("""
            INSERT INTO agent_personal_workspace_conversation_link_operation
              (operation_id, owner_jiacn, operation_type, idempotency_key, request_hash,
               operation_state, relation_id, created_at, completed_at,
               tenant_id, client_id, create_time, update_time)
            VALUES
              (#{operationId}, #{ownerJiacn}, #{operationType}, #{idempotencyKey}, #{requestHash},
               'PROCESSING', NULL, #{now}, NULL, #{tenantId}, #{clientId}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id), update_time=update_time
            """)
    int reserveOperation(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("operationType") String operationType,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("operationId") String operationId, @Param("now") long now);

    @Select("""
            SELECT id, operation_id, operation_type, idempotency_key, request_hash,
                   operation_state, relation_id
              FROM agent_personal_workspace_conversation_link_operation
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND operation_type=#{operationType}
               AND idempotency_key=#{idempotencyKey}
            """ + EXACT_SCOPE + """
               AND CAST(operation_type AS BINARY)=CAST(#{operationType} AS BINARY)
               AND OCTET_LENGTH(operation_type)=OCTET_LENGTH(#{operationType})
               AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
               AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1 FOR UPDATE
            """)
    OperationRow selectOperationForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("operationType") String operationType,
            @Param("idempotencyKey") String idempotencyKey);

    @Update("""
            UPDATE agent_personal_workspace_conversation_link_operation
               SET operation_state='COMMITTED', relation_id=#{relationId},
                   completed_at=#{completedAt}, update_time=#{completedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
               AND owner_jiacn=#{ownerJiacn} AND operation_id=#{operationId}
               AND operation_state='PROCESSING'
            """ + EXACT_SCOPE + """
               AND CAST(operation_id AS BINARY)=CAST(#{operationId} AS BINARY)
               AND OCTET_LENGTH(operation_id)=OCTET_LENGTH(#{operationId})
               AND CAST(operation_state AS BINARY)=CAST('PROCESSING' AS BINARY)
               AND OCTET_LENGTH(operation_state)=OCTET_LENGTH('PROCESSING')
            """)
    int commitOperation(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("operationId") String operationId, @Param("relationId") String relationId,
            @Param("completedAt") long completedAt);
}
