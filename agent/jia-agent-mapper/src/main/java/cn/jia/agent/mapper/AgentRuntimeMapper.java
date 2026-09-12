package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

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
            SELECT r.agent_id, r.name, r.avatar, r.owner_jiacn,
                   r.persona_code, r.persona_name, r.binding_id, r.abilities,
                   r.endpoint, r.status, r.current_task_id, r.current_task_title,
                   r.last_seen_at, r.error_message, r.client_id
            FROM agent_runtime r
            WHERE r.client_id = #{clientId}
              AND r.owner_jiacn = #{ownerJiacn}
              AND CAST(r.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(r.client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(r.owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(r.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND r.binding_id IS NOT NULL
              AND EXISTS (
                  SELECT 1
                  FROM agent_persona_binding b
                  INNER JOIN agent_identity_registry i ON i.binding_id = b.id
                  WHERE b.id = r.binding_id
                    AND b.status = 1
                    AND b.client_id = #{clientId}
                    AND b.jiacn = #{ownerJiacn}
                    AND CAST(b.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                    AND OCTET_LENGTH(b.client_id) = OCTET_LENGTH(#{clientId})
                    AND CAST(b.jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
                    AND OCTET_LENGTH(b.jiacn) = OCTET_LENGTH(#{ownerJiacn})
                    AND b.persona_code = r.persona_code
                    AND CAST(b.persona_code AS BINARY) = CAST(r.persona_code AS BINARY)
                    AND OCTET_LENGTH(b.persona_code) = OCTET_LENGTH(r.persona_code)
                    AND i.lifecycle_status = 'ACTIVE'
                    AND CAST(i.lifecycle_status AS BINARY) = CAST('ACTIVE' AS BINARY)
                    AND OCTET_LENGTH(i.lifecycle_status) = OCTET_LENGTH('ACTIVE')
                    AND (
                        (i.canonical_type = 'OPAQUE'
                         AND CAST(i.canonical_type AS BINARY) = CAST('OPAQUE' AS BINARY)
                         AND OCTET_LENGTH(i.canonical_type) = OCTET_LENGTH('OPAQUE'))
                        OR
                        (i.canonical_type = 'LEGACY_CANONICAL'
                         AND CAST(i.canonical_type AS BINARY) = CAST('LEGACY_CANONICAL' AS BINARY)
                         AND OCTET_LENGTH(i.canonical_type) = OCTET_LENGTH('LEGACY_CANONICAL'))
                    )
                    AND i.tenant_id = #{ownerJiacn}
                    AND i.client_id = #{clientId}
                    AND i.owner_jiacn = #{ownerJiacn}
                    AND CAST(i.tenant_id AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
                    AND OCTET_LENGTH(i.tenant_id) = OCTET_LENGTH(#{ownerJiacn})
                    AND CAST(i.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                    AND OCTET_LENGTH(i.client_id) = OCTET_LENGTH(#{clientId})
                    AND CAST(i.owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
                    AND OCTET_LENGTH(i.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                    AND i.canonical_agent_id = r.agent_id
                    AND CAST(i.canonical_agent_id AS BINARY) = CAST(r.agent_id AS BINARY)
                    AND OCTET_LENGTH(i.canonical_agent_id) = OCTET_LENGTH(r.agent_id)
                    AND (
                        (b.agent_id = i.canonical_agent_id
                         AND CAST(b.agent_id AS BINARY) = CAST(i.canonical_agent_id AS BINARY)
                         AND OCTET_LENGTH(b.agent_id) = OCTET_LENGTH(i.canonical_agent_id))
                        OR EXISTS (
                            SELECT 1
                            FROM agent_identity_alias a
                            WHERE a.registry_id = i.id
                              AND a.canonical_agent_id = i.canonical_agent_id
                              AND a.alias_value = b.agent_id
                              AND a.alias_type = 'LEGACY_AGENT_ID'
                              AND a.alias_status = 'ACTIVE'
                              AND a.valid_to IS NULL
                              AND a.client_id = #{clientId}
                              AND a.owner_jiacn = #{ownerJiacn}
                              AND a.tenant_id = #{ownerJiacn}
                              AND CAST(a.canonical_agent_id AS BINARY) = CAST(i.canonical_agent_id AS BINARY)
                              AND OCTET_LENGTH(a.canonical_agent_id) = OCTET_LENGTH(i.canonical_agent_id)
                              AND CAST(a.alias_value AS BINARY) = CAST(b.agent_id AS BINARY)
                              AND OCTET_LENGTH(a.alias_value) = OCTET_LENGTH(b.agent_id)
                              AND CAST(a.alias_type AS BINARY) = CAST('LEGACY_AGENT_ID' AS BINARY)
                              AND OCTET_LENGTH(a.alias_type) = OCTET_LENGTH('LEGACY_AGENT_ID')
                              AND CAST(a.alias_status AS BINARY) = CAST('ACTIVE' AS BINARY)
                              AND OCTET_LENGTH(a.alias_status) = OCTET_LENGTH('ACTIVE')
                              AND CAST(a.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                              AND OCTET_LENGTH(a.client_id) = OCTET_LENGTH(#{clientId})
                              AND CAST(a.owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
                              AND OCTET_LENGTH(a.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                              AND CAST(a.tenant_id AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
                              AND OCTET_LENGTH(a.tenant_id) = OCTET_LENGTH(#{ownerJiacn})
                        )
                    )
              )
            <if test="status != null and status.trim() != ''">
              AND r.status = #{status}
            </if>
            <if test="ability != null and ability.trim() != ''">
              AND r.abilities LIKE CONCAT('%', '"', #{ability}, '"', '%')
            </if>
            ORDER BY r.persona_code ASC
            </script>
            """)
    List<AgentRuntimeEntity> findActiveRosterByOwner(
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("status") String status,
            @Param("ability") String ability);

    @Select("""
            SELECT r.agent_id, r.name, r.avatar, r.owner_jiacn,
                   r.persona_code, r.persona_name, r.binding_id, r.abilities,
                   r.endpoint, r.status, r.current_task_id, r.current_task_title,
                   r.last_seen_at, r.error_message, r.client_id
            FROM agent_runtime r
            WHERE r.client_id = #{clientId}
              AND CAST(r.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(r.client_id) = OCTET_LENGTH(#{clientId})
              AND r.status IN ('online', 'busy')
              AND CAST(r.status AS BINARY) IN (CAST('online' AS BINARY), CAST('busy' AS BINARY))
              AND r.binding_id IS NOT NULL
              AND r.owner_jiacn IS NOT NULL
              AND EXISTS (
                  SELECT 1
                  FROM agent_persona_binding b
                  INNER JOIN agent_identity_registry i ON i.binding_id = b.id
                  WHERE b.id = r.binding_id
                    AND b.status = 1
                    AND b.client_id = r.client_id
                    AND b.jiacn = r.owner_jiacn
                    AND b.persona_code = r.persona_code
                    AND CAST(b.client_id AS BINARY) = CAST(r.client_id AS BINARY)
                    AND OCTET_LENGTH(b.client_id) = OCTET_LENGTH(r.client_id)
                    AND CAST(b.jiacn AS BINARY) = CAST(r.owner_jiacn AS BINARY)
                    AND OCTET_LENGTH(b.jiacn) = OCTET_LENGTH(r.owner_jiacn)
                    AND CAST(b.persona_code AS BINARY) = CAST(r.persona_code AS BINARY)
                    AND OCTET_LENGTH(b.persona_code) = OCTET_LENGTH(r.persona_code)
                    AND i.lifecycle_status = 'ACTIVE'
                    AND CAST(i.lifecycle_status AS BINARY) = CAST('ACTIVE' AS BINARY)
                    AND OCTET_LENGTH(i.lifecycle_status) = OCTET_LENGTH('ACTIVE')
                    AND (
                        (i.canonical_type = 'OPAQUE'
                         AND CAST(i.canonical_type AS BINARY) = CAST('OPAQUE' AS BINARY)
                         AND OCTET_LENGTH(i.canonical_type) = OCTET_LENGTH('OPAQUE'))
                        OR
                        (i.canonical_type = 'LEGACY_CANONICAL'
                         AND CAST(i.canonical_type AS BINARY) = CAST('LEGACY_CANONICAL' AS BINARY)
                         AND OCTET_LENGTH(i.canonical_type) = OCTET_LENGTH('LEGACY_CANONICAL'))
                    )
                    AND i.tenant_id = r.owner_jiacn
                    AND i.client_id = r.client_id
                    AND i.owner_jiacn = r.owner_jiacn
                    AND i.canonical_agent_id = r.agent_id
                    AND CAST(i.tenant_id AS BINARY) = CAST(r.owner_jiacn AS BINARY)
                    AND OCTET_LENGTH(i.tenant_id) = OCTET_LENGTH(r.owner_jiacn)
                    AND CAST(i.client_id AS BINARY) = CAST(r.client_id AS BINARY)
                    AND OCTET_LENGTH(i.client_id) = OCTET_LENGTH(r.client_id)
                    AND CAST(i.owner_jiacn AS BINARY) = CAST(r.owner_jiacn AS BINARY)
                    AND OCTET_LENGTH(i.owner_jiacn) = OCTET_LENGTH(r.owner_jiacn)
                    AND CAST(i.canonical_agent_id AS BINARY) = CAST(r.agent_id AS BINARY)
                    AND OCTET_LENGTH(i.canonical_agent_id) = OCTET_LENGTH(r.agent_id)
                    AND (
                        (b.agent_id = i.canonical_agent_id
                         AND CAST(b.agent_id AS BINARY) = CAST(i.canonical_agent_id AS BINARY)
                         AND OCTET_LENGTH(b.agent_id) = OCTET_LENGTH(i.canonical_agent_id))
                        OR EXISTS (
                            SELECT 1
                            FROM agent_identity_alias a
                            WHERE a.registry_id = i.id
                              AND a.canonical_agent_id = i.canonical_agent_id
                              AND a.alias_value = b.agent_id
                              AND a.alias_type = 'LEGACY_AGENT_ID'
                              AND a.alias_status = 'ACTIVE'
                              AND a.valid_to IS NULL
                              AND a.client_id = r.client_id
                              AND a.owner_jiacn = r.owner_jiacn
                              AND a.tenant_id = r.owner_jiacn
                              AND CAST(a.canonical_agent_id AS BINARY) = CAST(i.canonical_agent_id AS BINARY)
                              AND OCTET_LENGTH(a.canonical_agent_id) = OCTET_LENGTH(i.canonical_agent_id)
                              AND CAST(a.alias_value AS BINARY) = CAST(b.agent_id AS BINARY)
                              AND OCTET_LENGTH(a.alias_value) = OCTET_LENGTH(b.agent_id)
                              AND CAST(a.alias_type AS BINARY) = CAST('LEGACY_AGENT_ID' AS BINARY)
                              AND OCTET_LENGTH(a.alias_type) = OCTET_LENGTH('LEGACY_AGENT_ID')
                              AND CAST(a.alias_status AS BINARY) = CAST('ACTIVE' AS BINARY)
                              AND OCTET_LENGTH(a.alias_status) = OCTET_LENGTH('ACTIVE')
                              AND CAST(a.client_id AS BINARY) = CAST(r.client_id AS BINARY)
                              AND OCTET_LENGTH(a.client_id) = OCTET_LENGTH(r.client_id)
                              AND CAST(a.owner_jiacn AS BINARY) = CAST(r.owner_jiacn AS BINARY)
                              AND OCTET_LENGTH(a.owner_jiacn) = OCTET_LENGTH(r.owner_jiacn)
                              AND CAST(a.tenant_id AS BINARY) = CAST(r.owner_jiacn AS BINARY)
                              AND OCTET_LENGTH(a.tenant_id) = OCTET_LENGTH(r.owner_jiacn)
                        )
                    )
              )
            ORDER BY r.last_seen_at DESC
            """)
    List<AgentRuntimeEntity> findMapVisibleByExactClient(@Param("clientId") String clientId);

    @Update("""
            UPDATE agent_runtime
               SET client_id = NULL,
                   owner_jiacn = NULL,
                   persona_code = NULL,
                   persona_name = NULL,
                   binding_id = NULL,
                   endpoint = NULL,
                   token_hash = NULL,
                   current_task_id = NULL,
                   current_task_title = NULL,
                   error_message = NULL,
                   status = 'offline',
                   last_seen_at = #{detachedAt},
                   update_time = #{detachedAt}
             WHERE id = #{runtimeId}
               AND binding_id = #{bindingId}
               AND agent_id = #{agentId}
               AND client_id = #{clientId}
               AND owner_jiacn = #{ownerJiacn}
               AND CAST(agent_id AS BINARY) = CAST(#{agentId} AS BINARY)
               AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{agentId})
               AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            """)
    int clearBindingAfterUnbind(
            @Param("runtimeId") long runtimeId,
            @Param("agentId") String agentId,
            @Param("bindingId") long bindingId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("detachedAt") long detachedAt);
}
