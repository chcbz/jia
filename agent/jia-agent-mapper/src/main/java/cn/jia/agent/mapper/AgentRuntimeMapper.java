package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentRuntimeMapper extends BaseMapper<AgentRuntimeEntity> {
    @Select("""
            SELECT *
            FROM agent_runtime
            WHERE agent_id = #{agentId}
              AND CAST(agent_id AS BINARY) = CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{agentId})
            LIMIT 1
            """)
    AgentRuntimeEntity findExactByAgentId(@Param("agentId") String agentId);

    @Select("""
            SELECT *
            FROM agent_runtime
            WHERE agent_id = #{agentId}
              AND CAST(agent_id AS BINARY) = CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{agentId})
            LIMIT 1
            FOR UPDATE
            """)
    AgentRuntimeEntity findExactByAgentIdForUpdate(@Param("agentId") String agentId);

    @Select("""
            <script>
            SELECT id,agent_id,name,avatar,owner_jiacn,persona_code,persona_name,binding_id,
                   abilities,endpoint,token_hash,status,current_task_id,current_task_title,
                   last_seen_at,error_message,create_time,update_time,tenant_id,client_id,
                   output_capabilities_json,output_capabilities_runtime_id,
                   output_capabilities_updated_at
            FROM agent_runtime
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND owner_jiacn=#{ownerJiacn} AND agent_id=#{agentId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
            LIMIT 1
            <if test="forUpdate">FOR UPDATE</if>
            </script>
            """)
    AgentRuntimeEntity findExactOutputRuntime(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId,
            @Param("forUpdate") boolean forUpdate);

    @Update("""
            UPDATE agent_runtime
            SET output_capabilities_json=#{capabilitiesJson},
                output_capabilities_runtime_id=#{runtimeInstanceId},
                output_capabilities_updated_at=CASE WHEN #{runtimeInstanceId} IS NULL
                    THEN NULL ELSE #{updatedAt} END,
                update_time=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
              AND agent_id=#{agentId} AND binding_id=#{bindingId} AND token_hash=#{registrationToken}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
              AND CAST(token_hash AS BINARY)=CAST(#{registrationToken} AS BINARY)
              AND OCTET_LENGTH(token_hash)=OCTET_LENGTH(#{registrationToken})
            """)
    int replaceOutputCapabilities(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId,
            @Param("bindingId") long bindingId,
            @Param("registrationToken") String registrationToken,
            @Param("runtimeInstanceId") String runtimeInstanceId,
            @Param("capabilitiesJson") String capabilitiesJson,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE agent_runtime
            SET output_capabilities_json=#{capabilitiesJson},output_capabilities_updated_at=#{updatedAt},
                update_time=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
              AND agent_id=#{agentId} AND binding_id=#{bindingId}
              AND output_capabilities_runtime_id=#{runtimeInstanceId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(agent_id AS BINARY)=CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId})
              AND CAST(output_capabilities_runtime_id AS BINARY)=CAST(#{runtimeInstanceId} AS BINARY)
              AND OCTET_LENGTH(output_capabilities_runtime_id)=OCTET_LENGTH(#{runtimeInstanceId})
            """)
    int refreshOutputCapabilities(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId,
            @Param("bindingId") long bindingId,
            @Param("runtimeInstanceId") String runtimeInstanceId,
            @Param("capabilitiesJson") String capabilitiesJson,
            @Param("updatedAt") long updatedAt);
}
