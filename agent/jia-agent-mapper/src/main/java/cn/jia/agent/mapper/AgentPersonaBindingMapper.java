package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
            """)
    AgentPersonaBindingEntity findExactActiveByScopeAndPersona(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("personaCode") String personaCode,
            @Param("activeStatus") int activeStatus);

    @Select("""
            SELECT b.id AS binding_id,
                   b.tenant_id AS binding_tenant_id,
                   b.client_id AS binding_client_id,
                   b.owner_jiacn AS binding_owner_jiacn,
                   b.persona_code,
                   b.agent_id AS binding_agent_id,
                   b.status AS binding_status,
                   i.id AS identity_id,
                   i.binding_id AS identity_binding_id,
                   i.tenant_id AS identity_tenant_id,
                   i.client_id AS identity_client_id,
                   i.owner_jiacn AS identity_owner_jiacn,
                   i.canonical_agent_id,
                   i.canonical_type,
                   i.lifecycle_status,
                   CASE
                     WHEN b.agent_id = i.canonical_agent_id
                      AND CAST(b.agent_id AS BINARY) = CAST(i.canonical_agent_id AS BINARY)
                      AND OCTET_LENGTH(b.agent_id) = OCTET_LENGTH(i.canonical_agent_id)
                     THEN 1
                     WHEN EXISTS (
                       SELECT 1
                       FROM agent_identity_alias a
                       WHERE a.registry_id = i.id
                         AND a.canonical_agent_id = i.canonical_agent_id
                         AND a.alias_value = b.agent_id
                         AND a.alias_type = 'LEGACY_AGENT_ID'
                         AND a.alias_status = 'ACTIVE'
                         AND a.valid_to IS NULL
                         AND a.tenant_id = #{tenantId}
                         AND a.client_id = #{clientId}
                         AND a.owner_jiacn = #{ownerJiacn}
                         AND CAST(a.canonical_agent_id AS BINARY) = CAST(i.canonical_agent_id AS BINARY)
                         AND OCTET_LENGTH(a.canonical_agent_id) = OCTET_LENGTH(i.canonical_agent_id)
                         AND CAST(a.alias_value AS BINARY) = CAST(b.agent_id AS BINARY)
                         AND OCTET_LENGTH(a.alias_value) = OCTET_LENGTH(b.agent_id)
                         AND CAST(a.alias_type AS BINARY) = CAST('LEGACY_AGENT_ID' AS BINARY)
                         AND OCTET_LENGTH(a.alias_type) = OCTET_LENGTH('LEGACY_AGENT_ID')
                         AND CAST(a.alias_status AS BINARY) = CAST('ACTIVE' AS BINARY)
                         AND OCTET_LENGTH(a.alias_status) = OCTET_LENGTH('ACTIVE')
                         AND CAST(a.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
                         AND OCTET_LENGTH(a.tenant_id) = OCTET_LENGTH(#{tenantId})
                         AND CAST(a.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                         AND OCTET_LENGTH(a.client_id) = OCTET_LENGTH(#{clientId})
                         AND CAST(a.owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
                         AND OCTET_LENGTH(a.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                     ) THEN 1
                     ELSE 0
                   END AS agent_reference_valid,
                   r.id AS runtime_id,
                   r.tenant_id AS runtime_tenant_id,
                   r.client_id AS runtime_client_id,
                   r.owner_jiacn AS runtime_owner_jiacn,
                   r.binding_id AS runtime_binding_id,
                   r.agent_id AS runtime_agent_id,
                   r.abilities AS runtime_abilities,
                   r.status AS runtime_status
            FROM agent_persona_binding b
            INNER JOIN agent_persona p
              ON p.persona_code = b.persona_code
             AND CAST(p.persona_code AS BINARY) = CAST(b.persona_code AS BINARY)
             AND OCTET_LENGTH(p.persona_code) = OCTET_LENGTH(b.persona_code)
             AND p.active = 1
             AND (
                   (p.tenant_id = '0'
                    AND CAST(p.tenant_id AS BINARY) = CAST('0' AS BINARY)
                    AND OCTET_LENGTH(p.tenant_id) = OCTET_LENGTH('0'))
                   OR
                   (p.tenant_id = #{tenantId}
                    AND CAST(p.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
                    AND OCTET_LENGTH(p.tenant_id) = OCTET_LENGTH(#{tenantId}))
                 )
             AND (
                   p.client_id IS NULL
                   OR
                   (p.client_id = #{clientId}
                    AND CAST(p.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                    AND OCTET_LENGTH(p.client_id) = OCTET_LENGTH(#{clientId}))
                 )
            LEFT JOIN agent_identity_registry i ON i.binding_id = b.id
            LEFT JOIN agent_runtime r
              ON r.agent_id = i.canonical_agent_id
             AND CAST(r.agent_id AS BINARY) = CAST(i.canonical_agent_id AS BINARY)
             AND OCTET_LENGTH(r.agent_id) = OCTET_LENGTH(i.canonical_agent_id)
            WHERE b.tenant_id = #{tenantId}
              AND b.client_id = #{clientId}
              AND b.owner_jiacn = #{ownerJiacn}
              AND b.status = #{activeStatus}
              AND CAST(b.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(b.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(b.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(b.client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(b.owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(b.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            ORDER BY b.persona_code ASC
            """)
    List<AgentPersonaCatalogBindingRow> findCatalogOverlay(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("activeStatus") int activeStatus);

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
