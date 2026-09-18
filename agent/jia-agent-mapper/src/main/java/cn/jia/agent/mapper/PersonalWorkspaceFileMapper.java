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
}
