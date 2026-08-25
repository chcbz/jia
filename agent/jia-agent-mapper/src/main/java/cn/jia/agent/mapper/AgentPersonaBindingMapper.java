package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentPersonaBindingMapper extends BaseMapper<AgentPersonaBindingEntity> {
    @Select("SELECT * FROM agent_persona_binding WHERE id = #{id} FOR UPDATE")
    AgentPersonaBindingEntity selectByIdForUpdate(@Param("id") long id);

    @Select("""
            SELECT *
            FROM agent_persona_binding
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND owner_jiacn = #{ownerJiacn}
              AND persona_code = #{personaCode}
              AND status = #{activeStatus}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(persona_code AS BINARY) = CAST(#{personaCode} AS BINARY)
              AND OCTET_LENGTH(persona_code) = OCTET_LENGTH(#{personaCode})
            LIMIT 1
            FOR UPDATE
            """)
    AgentPersonaBindingEntity findExactActiveByScopeAndPersonaForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("personaCode") String personaCode,
            @Param("activeStatus") int activeStatus);

    @Select("""
            SELECT *
            FROM agent_persona_binding
            WHERE client_id = #{clientId}
              AND jiacn = #{jiacn}
              AND agent_id = #{agentId}
              AND status = #{activeStatus}
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(jiacn AS BINARY) = CAST(#{jiacn} AS BINARY)
              AND OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{jiacn})
              AND CAST(agent_id AS BINARY) = CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{agentId})
            LIMIT 1
            """)
    AgentPersonaBindingEntity findExactActiveByOwner(
            @Param("clientId") String clientId,
            @Param("jiacn") String jiacn,
            @Param("agentId") String agentId,
            @Param("activeStatus") int activeStatus);

    @Select("""
            SELECT *
            FROM agent_persona_binding
            WHERE client_id = #{clientId}
              AND jiacn = #{jiacn}
              AND agent_id = #{agentId}
              AND status = #{activeStatus}
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(jiacn AS BINARY) = CAST(#{jiacn} AS BINARY)
              AND OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{jiacn})
              AND CAST(agent_id AS BINARY) = CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{agentId})
            LIMIT 1
            FOR UPDATE
            """)
    AgentPersonaBindingEntity findExactActiveByOwnerForUpdate(
            @Param("clientId") String clientId,
            @Param("jiacn") String jiacn,
            @Param("agentId") String agentId,
            @Param("activeStatus") int activeStatus);
}
