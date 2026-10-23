package cn.jia.agent.mapper;

import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PersonalWorkspaceFileMapper extends BaseMapper<PersonalWorkspaceFileEntity> {
    @Select("""
            SELECT * FROM agent_personal_workspace_file
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND file_id=#{fileId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(file_id AS BINARY)=CAST(#{fileId} AS BINARY)
               AND OCTET_LENGTH(file_id)=OCTET_LENGTH(#{fileId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceFileEntity selectForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId);

    /** One statement: file revision and all counts share the same InnoDB read view. */
    @Select("""
            SELECT f.file_id AS fileId, f.metadata_revision AS impactRevision,
                   (SELECT COUNT(*) FROM agent_personal_workspace_task_file_link r WHERE r.tenant_id=f.tenant_id AND r.client_id=f.client_id AND r.owner_jiacn=f.owner_jiacn AND r.file_id=f.file_id
                     AND CAST(r.tenant_id AS BINARY)=CAST(f.tenant_id AS BINARY) AND CAST(r.client_id AS BINARY)=CAST(f.client_id AS BINARY) AND CAST(r.owner_jiacn AS BINARY)=CAST(f.owner_jiacn AS BINARY) AND CAST(r.file_id AS BINARY)=CAST(f.file_id AS BINARY) AND r.link_state='ACTIVE') AS taskReferences,
                   (SELECT COUNT(*) FROM agent_personal_workspace_conversation_file_link r WHERE r.tenant_id=f.tenant_id AND r.client_id=f.client_id AND r.owner_jiacn=f.owner_jiacn AND r.file_id=f.file_id
                     AND CAST(r.tenant_id AS BINARY)=CAST(f.tenant_id AS BINARY) AND CAST(r.client_id AS BINARY)=CAST(f.client_id AS BINARY) AND CAST(r.owner_jiacn AS BINARY)=CAST(f.owner_jiacn AS BINARY) AND CAST(r.file_id AS BINARY)=CAST(f.file_id AS BINARY) AND r.link_state='ACTIVE') AS conversationReferences,
                   (SELECT COUNT(*) FROM agent_personal_workspace_execution_input r WHERE r.tenant_id=f.tenant_id AND r.client_id=f.client_id AND r.owner_jiacn=f.owner_jiacn AND r.file_id=f.file_id
                     AND CAST(r.tenant_id AS BINARY)=CAST(f.tenant_id AS BINARY) AND CAST(r.client_id AS BINARY)=CAST(f.client_id AS BINARY) AND CAST(r.owner_jiacn AS BINARY)=CAST(f.owner_jiacn AS BINARY) AND CAST(r.file_id AS BINARY)=CAST(f.file_id AS BINARY) AND r.grant_state='ACTIVE') AS activeInputGrants
              FROM agent_personal_workspace_file f
             WHERE f.tenant_id=#{tenantId} AND f.client_id=#{clientId}
               AND f.owner_jiacn=#{ownerJiacn} AND f.file_id=#{fileId}
               AND CAST(f.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND CAST(f.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND CAST(f.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND CAST(f.file_id AS BINARY)=CAST(#{fileId} AS BINARY)
            """)
    cn.jia.agent.dao.PersonalWorkspaceDao.UsageSnapshot selectUsage(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId);
}
