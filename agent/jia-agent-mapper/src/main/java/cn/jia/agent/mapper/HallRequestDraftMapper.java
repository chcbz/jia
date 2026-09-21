package cn.jia.agent.mapper;

import cn.jia.agent.entity.HallExecutionResultRow;
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
               AND draft_id=#{draftId}
            """ + EXACT_SCOPE + """
               AND CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)
               AND OCTET_LENGTH(draft_id)=OCTET_LENGTH(#{draftId})
             LIMIT 1 FOR UPDATE
            """)
    HallRequestDraftEntity lockExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("draftId") String draftId);

    @Select("SELECT " + COLUMNS + """
              FROM hall_request_draft
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND submit_key=#{submitKey}
            """ + EXACT_SCOPE + """
               AND CAST(submit_key AS BINARY)=CAST(#{submitKey} AS BINARY)
               AND OCTET_LENGTH(submit_key)=OCTET_LENGTH(#{submitKey})
             LIMIT 1
            """)
    HallRequestDraftEntity findBySubmitKey(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("submitKey") String submitKey);

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
            UPDATE IGNORE hall_request_draft
               SET submit_key=#{submitKey},submit_hash=#{submitHash},updated_at=#{updatedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND draft_id=#{draftId} AND state='EDITING' AND revision=#{expectedRevision}
               AND submit_key IS NULL AND submit_hash IS NULL
            """ + EXACT_SCOPE + """
               AND CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)
               AND OCTET_LENGTH(draft_id)=OCTET_LENGTH(#{draftId})
               AND CAST(state AS BINARY)=CAST('EDITING' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('EDITING')
            """)
    int reserveSubmitIntent(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("draftId") String draftId, @Param("expectedRevision") long expectedRevision,
            @Param("submitKey") String submitKey, @Param("submitHash") String submitHash,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE hall_request_draft
               SET state='SUBMITTED',case_id=#{caseId},submission_ref=#{submissionRef},
                   submitted_execution_id=#{submittedExecutionId},revision=revision+1,
                   updated_at=#{updatedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND draft_id=#{draftId} AND state='EDITING' AND revision=#{expectedRevision}
               AND submit_key=#{submitKey} AND submit_hash=#{submitHash}
            """ + EXACT_SCOPE + """
               AND CAST(draft_id AS BINARY)=CAST(#{draftId} AS BINARY)
               AND OCTET_LENGTH(draft_id)=OCTET_LENGTH(#{draftId})
               AND CAST(state AS BINARY)=CAST('EDITING' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('EDITING')
               AND CAST(submit_key AS BINARY)=CAST(#{submitKey} AS BINARY)
               AND OCTET_LENGTH(submit_key)=OCTET_LENGTH(#{submitKey})
            """)
    int markSubmitted(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("draftId") String draftId, @Param("expectedRevision") long expectedRevision,
            @Param("submitKey") String submitKey, @Param("submitHash") String submitHash,
            @Param("caseId") String caseId, @Param("submissionRef") String submissionRef,
            @Param("submittedExecutionId") String submittedExecutionId,
            @Param("updatedAt") long updatedAt);

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

    @Select("""
            SELECT e.execution_id
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
             LIMIT 1 FOR UPDATE
            """)
    String lockPrivateCommittedOutput(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId, @Param("outputId") String outputId,
            @Param("fileId") String fileId, @Param("fileVersion") int fileVersion);

    @Select("""
            SELECT e.tenant_id,e.client_id,e.owner_jiacn,e.execution_id,e.task_id,e.run_id,
                   e.execution_mode,e.execution_state,
                   o.output_id,o.workspace_file_id,o.workspace_file_version,
                   o.original_filename AS output_original_filename,
                   o.content_mime_type AS output_content_mime_type,
                   o.byte_length AS output_byte_length,o.content_hash AS output_content_hash,
                   o.output_state,o.publication_state,f.state AS file_state,
                   v.original_filename AS file_original_filename,
                   v.content_mime_type AS file_content_mime_type,
                   v.byte_length AS file_byte_length,v.content_hash AS file_content_hash
              FROM agent_personal_workspace_execution e
              LEFT JOIN agent_personal_workspace_execution_output o
                ON o.tenant_id=e.tenant_id AND o.client_id=e.client_id
               AND o.owner_jiacn=e.owner_jiacn AND o.execution_id=e.execution_id
               AND CAST(o.tenant_id AS BINARY)=CAST(e.tenant_id AS BINARY)
               AND OCTET_LENGTH(o.tenant_id)=OCTET_LENGTH(e.tenant_id)
               AND CAST(o.client_id AS BINARY)=CAST(e.client_id AS BINARY)
               AND OCTET_LENGTH(o.client_id)=OCTET_LENGTH(e.client_id)
               AND CAST(o.owner_jiacn AS BINARY)=CAST(e.owner_jiacn AS BINARY)
               AND OCTET_LENGTH(o.owner_jiacn)=OCTET_LENGTH(e.owner_jiacn)
               AND CAST(o.execution_id AS BINARY)=CAST(e.execution_id AS BINARY)
               AND OCTET_LENGTH(o.execution_id)=OCTET_LENGTH(e.execution_id)
              LEFT JOIN agent_personal_workspace_file f
                ON f.tenant_id=o.tenant_id AND f.client_id=o.client_id
               AND f.owner_jiacn=o.owner_jiacn AND f.file_id=o.workspace_file_id
               AND CAST(f.tenant_id AS BINARY)=CAST(o.tenant_id AS BINARY)
               AND OCTET_LENGTH(f.tenant_id)=OCTET_LENGTH(o.tenant_id)
               AND CAST(f.client_id AS BINARY)=CAST(o.client_id AS BINARY)
               AND OCTET_LENGTH(f.client_id)=OCTET_LENGTH(o.client_id)
               AND CAST(f.owner_jiacn AS BINARY)=CAST(o.owner_jiacn AS BINARY)
               AND OCTET_LENGTH(f.owner_jiacn)=OCTET_LENGTH(o.owner_jiacn)
               AND CAST(f.file_id AS BINARY)=CAST(o.workspace_file_id AS BINARY)
               AND OCTET_LENGTH(f.file_id)=OCTET_LENGTH(o.workspace_file_id)
              LEFT JOIN agent_personal_workspace_file_version v
                ON v.tenant_id=o.tenant_id AND v.client_id=o.client_id
               AND v.owner_jiacn=o.owner_jiacn AND v.file_id=o.workspace_file_id
               AND v.version=o.workspace_file_version
               AND CAST(v.tenant_id AS BINARY)=CAST(o.tenant_id AS BINARY)
               AND OCTET_LENGTH(v.tenant_id)=OCTET_LENGTH(o.tenant_id)
               AND CAST(v.client_id AS BINARY)=CAST(o.client_id AS BINARY)
               AND OCTET_LENGTH(v.client_id)=OCTET_LENGTH(o.client_id)
               AND CAST(v.owner_jiacn AS BINARY)=CAST(o.owner_jiacn AS BINARY)
               AND OCTET_LENGTH(v.owner_jiacn)=OCTET_LENGTH(o.owner_jiacn)
               AND CAST(v.file_id AS BINARY)=CAST(o.workspace_file_id AS BINARY)
               AND OCTET_LENGTH(v.file_id)=OCTET_LENGTH(o.workspace_file_id)
             WHERE e.tenant_id=#{tenantId} AND e.client_id=#{clientId}
               AND e.owner_jiacn=#{ownerJiacn} AND e.execution_id=#{executionId}
               AND e.execution_mode='PRIVATE'
               AND CAST(e.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(e.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(e.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(e.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(e.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(e.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(e.execution_id AS BINARY)=CAST(#{executionId} AS BINARY)
               AND OCTET_LENGTH(e.execution_id)=OCTET_LENGTH(#{executionId})
               AND CAST(e.execution_mode AS BINARY)=CAST('PRIVATE' AS BINARY)
               AND OCTET_LENGTH(e.execution_mode)=OCTET_LENGTH('PRIVATE')
             ORDER BY CAST(o.output_id AS BINARY) ASC
            """)
    List<HallExecutionResultRow> listPrivateExecutionResults(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("executionId") String executionId);

}
