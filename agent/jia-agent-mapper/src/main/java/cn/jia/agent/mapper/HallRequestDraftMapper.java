package cn.jia.agent.mapper;

import cn.jia.agent.entity.HallRequestDraftEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface HallRequestDraftMapper extends BaseMapper<HallRequestDraftEntity> {
    String EXACT_SCOPE = """
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;
    String COLUMNS = """
            draft_id,tenant_id,client_id,owner_jiacn,kind,origin_ref,
            source_type,source_id,source_version,case_id,task_id,conversation_id,
            title,instruction,target_agent_id,output_mime,inputs_json,source_output_ref_json,
            ui_checkpoint_json,revision,state,submission_ref,submitted_execution_id,
            create_key,create_hash,submit_key,submit_hash,discard_key,discard_hash,
            created_at,updated_at
            """;

    @Select("SELECT " + COLUMNS + """
              FROM hall_request_draft
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND draft_id=#{draftId}
            """ + EXACT_SCOPE + """
               AND CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)
               AND OCTET_LENGTH(draft_id)=OCTET_LENGTH(#{draftId})
             LIMIT 1
            """)
    HallRequestDraftEntity findExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("draftId") String draftId);

    @Select("SELECT " + COLUMNS + """
              FROM hall_request_draft
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND create_key=#{createKey}
            """ + EXACT_SCOPE + """
               AND CAST(create_key AS BINARY)=CAST(#{createKey} AS BINARY)
               AND OCTET_LENGTH(create_key)=OCTET_LENGTH(#{createKey})
             LIMIT 1
            """)
    HallRequestDraftEntity findByCreateKey(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("createKey") String createKey);

    @Select("SELECT " + COLUMNS + """
              FROM hall_request_draft
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND discard_key=#{discardKey}
            """ + EXACT_SCOPE + """
               AND CAST(discard_key AS BINARY)=CAST(#{discardKey} AS BINARY)
               AND OCTET_LENGTH(discard_key)=OCTET_LENGTH(#{discardKey})
             LIMIT 1
            """)
    HallRequestDraftEntity findByDiscardKey(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("discardKey") String discardKey);

    @Select("""
            <script>
            SELECT """ + COLUMNS + """
              FROM hall_request_draft
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND state='EDITING'
            """ + EXACT_SCOPE + """
               AND CAST(state AS BINARY)=CAST('EDITING' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('EDITING')
            <if test='beforeUpdatedAt != null and beforeDraftId != null'>
               AND (updated_at &lt; #{beforeUpdatedAt}
                    OR (updated_at=#{beforeUpdatedAt}
                        AND CAST(draft_id AS BINARY) &lt; CAST(#{beforeDraftId} AS BINARY)))
            </if>
             ORDER BY updated_at DESC, CAST(draft_id AS BINARY) DESC
             LIMIT #{limit}
            </script>
            """)
    List<HallRequestDraftEntity> listEditing(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("beforeUpdatedAt") Long beforeUpdatedAt,
            @Param("beforeDraftId") String beforeDraftId, @Param("limit") int limit);

    @Insert("""
            INSERT INTO hall_request_draft
              (draft_id,tenant_id,client_id,owner_jiacn,kind,origin_ref,
               source_type,source_id,source_version,case_id,task_id,conversation_id,
               title,instruction,target_agent_id,output_mime,inputs_json,source_output_ref_json,
               ui_checkpoint_json,revision,state,submission_ref,submitted_execution_id,
               create_key,create_hash,submit_key,submit_hash,discard_key,discard_hash,
               created_at,updated_at)
            VALUES
              (#{draftId},#{tenantId},#{clientId},#{ownerJiacn},#{kind},#{originRef},
               #{sourceType},#{sourceId},#{sourceVersion},#{caseId},#{taskId},#{conversationId},
               #{title},#{instruction},#{targetAgentId},#{outputMime},#{inputsJson},#{sourceOutputRefJson},
               #{uiCheckpointJson},#{revision},#{state},#{submissionRef},#{submittedExecutionId},
               #{createKey},#{createHash},#{submitKey},#{submitHash},#{discardKey},#{discardHash},
               #{createdAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE draft_id=draft_id
            """)
    int reserveCreate(HallRequestDraftEntity entity);

    @Update("""
            UPDATE hall_request_draft
               SET title=#{title},instruction=#{instruction},target_agent_id=#{targetAgentId},
                   output_mime=#{outputMime},inputs_json=#{inputsJson},
                   revision=revision+1,updated_at=#{updatedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND draft_id=#{draftId} AND state='EDITING' AND revision=#{expectedRevision}
            """ + EXACT_SCOPE + """
               AND CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)
               AND OCTET_LENGTH(draft_id)=OCTET_LENGTH(#{draftId})
               AND CAST(state AS BINARY)=CAST('EDITING' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('EDITING')
            """)
    int replaceEditing(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("draftId") String draftId, @Param("expectedRevision") long expectedRevision,
            @Param("title") String title, @Param("instruction") String instruction,
            @Param("targetAgentId") String targetAgentId, @Param("outputMime") String outputMime,
            @Param("inputsJson") String inputsJson, @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE hall_request_draft
               SET state='DISCARDED',discard_key=#{discardKey},discard_hash=#{discardHash},
                   revision=revision+1,updated_at=#{updatedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND draft_id=#{draftId} AND state='EDITING' AND revision=#{expectedRevision}
            """ + EXACT_SCOPE + """
               AND CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)
               AND OCTET_LENGTH(draft_id)=OCTET_LENGTH(#{draftId})
               AND CAST(state AS BINARY)=CAST('EDITING' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('EDITING')
            """)
    int discardEditing(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("draftId") String draftId, @Param("expectedRevision") long expectedRevision,
            @Param("discardKey") String discardKey, @Param("discardHash") String discardHash,
            @Param("updatedAt") long updatedAt);

    @Select("""
            SELECT COUNT(*)
              FROM agent_personal_workspace_execution e
              JOIN agent_personal_workspace_execution_output o
                ON o.tenant_id=e.tenant_id AND o.client_id=e.client_id
               AND o.owner_jiacn=e.owner_jiacn AND o.execution_id=e.execution_id
              JOIN agent_personal_workspace_file_version v
                ON v.tenant_id=o.tenant_id AND v.client_id=o.client_id
               AND v.owner_jiacn=o.owner_jiacn AND v.file_id=o.workspace_file_id
               AND v.version=o.workspace_file_version
              JOIN agent_personal_workspace_file f
                ON f.tenant_id=o.tenant_id AND f.client_id=o.client_id
               AND f.owner_jiacn=o.owner_jiacn AND f.file_id=o.workspace_file_id
             WHERE e.tenant_id=#{tenantId} AND e.client_id=#{clientId} AND e.owner_jiacn=#{ownerJiacn}
               AND e.execution_id=#{executionId} AND o.output_id=#{outputId}
               AND o.workspace_file_id=#{fileId} AND o.workspace_file_version=#{fileVersion}
               AND e.execution_mode='PRIVATE' AND e.execution_state='OUTPUT_COMMITTED'
               AND o.output_state='COMMITTED' AND o.publication_state='PENDING'
               AND f.state='ACTIVE'
               AND CAST(e.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(e.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(e.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(e.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(e.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(e.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(e.execution_id AS BINARY)=CAST(#{executionId} AS BINARY)
               AND OCTET_LENGTH(e.execution_id)=OCTET_LENGTH(#{executionId})
               AND CAST(o.output_id AS BINARY)=CAST(#{outputId} AS BINARY)
               AND OCTET_LENGTH(o.output_id)=OCTET_LENGTH(#{outputId})
               AND CAST(o.workspace_file_id AS BINARY)=CAST(#{fileId} AS BINARY)
               AND OCTET_LENGTH(o.workspace_file_id)=OCTET_LENGTH(#{fileId})
            """)
    int countPrivateCommittedOutput(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId, @Param("outputId") String outputId,
            @Param("fileId") String fileId, @Param("fileVersion") int fileVersion);
}
