package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentSceneStateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentSceneStateMapper extends BaseMapper<AgentSceneStateEntity> {
    @Select("""
            SELECT snapshot_scope.scopeTenantId,
                   snapshot_scope.scopeClientId,
                   snapshot_scope.scopeSceneId,
                   snapshot_scope.sceneVersion,
                   runtime.agent_id AS agentId,
                   runtime.persona_code AS personaCode,
                   runtime.status AS status,
                   state.agent_id AS stateAgentId,
                   state.persona_code AS statePersonaCode,
                   state.behavior AS behavior,
                   state.origin_region_id AS originRegionId,
                   state.target_region_id AS targetRegionId,
                   state.related_type AS relatedType,
                   state.related_id AS relatedId,
                   state.phase AS phase,
                   state.state_version AS stateVersion,
                   state.started_at AS startedAt,
                   state.expected_arrival_at AS expectedArrivalAt,
                   state.expires_at AS expiresAt
            FROM (
                SELECT #{tenantId} AS scopeTenantId,
                       #{clientId} AS scopeClientId,
                       #{sceneId} AS scopeSceneId,
                       COALESCE((
                           SELECT scene_version_row.current_version
                           FROM agent_scene_version scene_version_row
                           WHERE scene_version_row.tenant_id = #{tenantId}
                             AND scene_version_row.client_id = #{clientId}
                             AND scene_version_row.scene_id = #{sceneId}
                             AND CAST(scene_version_row.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
                             AND OCTET_LENGTH(scene_version_row.tenant_id) = OCTET_LENGTH(#{tenantId})
                             AND CAST(scene_version_row.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                             AND OCTET_LENGTH(scene_version_row.client_id) = OCTET_LENGTH(#{clientId})
                             AND CAST(scene_version_row.scene_id AS BINARY) = CAST(#{sceneId} AS BINARY)
                             AND OCTET_LENGTH(scene_version_row.scene_id) = OCTET_LENGTH(#{sceneId})
                           LIMIT 1
                       ), 0) AS sceneVersion
            ) snapshot_scope
            LEFT JOIN agent_runtime runtime
              ON runtime.client_id = #{clientId}
             AND runtime.owner_jiacn = #{tenantId}
             AND CAST(runtime.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
             AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
             AND CAST(runtime.owner_jiacn AS BINARY) = CAST(#{tenantId} AS BINARY)
             AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{tenantId})
             AND runtime.status IN ('online', 'busy')
             AND CAST(runtime.status AS BINARY) IN (CAST('online' AS BINARY), CAST('busy' AS BINARY))
             AND runtime.binding_id IS NOT NULL
             AND EXISTS (
                 SELECT 1
                 FROM agent_persona_binding binding
                 INNER JOIN agent_identity_registry identity
                   ON identity.binding_id = binding.id
                 WHERE binding.id = runtime.binding_id
                   AND binding.status = 1
                   AND binding.client_id = #{clientId}
                   AND binding.jiacn = #{tenantId}
                   AND binding.persona_code = runtime.persona_code
                   AND CAST(binding.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                   AND OCTET_LENGTH(binding.client_id) = OCTET_LENGTH(#{clientId})
                   AND CAST(binding.jiacn AS BINARY) = CAST(#{tenantId} AS BINARY)
                   AND OCTET_LENGTH(binding.jiacn) = OCTET_LENGTH(#{tenantId})
                   AND CAST(binding.persona_code AS BINARY) = CAST(runtime.persona_code AS BINARY)
                   AND OCTET_LENGTH(binding.persona_code) = OCTET_LENGTH(runtime.persona_code)
                   AND identity.lifecycle_status = 'ACTIVE'
                   AND CAST(identity.lifecycle_status AS BINARY) = CAST('ACTIVE' AS BINARY)
                   AND OCTET_LENGTH(identity.lifecycle_status) = OCTET_LENGTH('ACTIVE')
                   AND (
                       (identity.canonical_type = 'OPAQUE'
                        AND CAST(identity.canonical_type AS BINARY) = CAST('OPAQUE' AS BINARY)
                        AND OCTET_LENGTH(identity.canonical_type) = OCTET_LENGTH('OPAQUE'))
                       OR
                       (identity.canonical_type = 'LEGACY_CANONICAL'
                        AND CAST(identity.canonical_type AS BINARY) = CAST('LEGACY_CANONICAL' AS BINARY)
                        AND OCTET_LENGTH(identity.canonical_type) = OCTET_LENGTH('LEGACY_CANONICAL'))
                   )
                   AND identity.tenant_id = #{tenantId}
                   AND identity.client_id = #{clientId}
                   AND identity.owner_jiacn = #{tenantId}
                   AND CAST(identity.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
                   AND OCTET_LENGTH(identity.tenant_id) = OCTET_LENGTH(#{tenantId})
                   AND CAST(identity.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                   AND OCTET_LENGTH(identity.client_id) = OCTET_LENGTH(#{clientId})
                   AND CAST(identity.owner_jiacn AS BINARY) = CAST(#{tenantId} AS BINARY)
                   AND OCTET_LENGTH(identity.owner_jiacn) = OCTET_LENGTH(#{tenantId})
                   AND identity.canonical_agent_id = runtime.agent_id
                   AND CAST(identity.canonical_agent_id AS BINARY) = CAST(runtime.agent_id AS BINARY)
                   AND OCTET_LENGTH(identity.canonical_agent_id) = OCTET_LENGTH(runtime.agent_id)
                   AND (
                       (binding.agent_id = identity.canonical_agent_id
                        AND CAST(binding.agent_id AS BINARY) = CAST(identity.canonical_agent_id AS BINARY)
                        AND OCTET_LENGTH(binding.agent_id) = OCTET_LENGTH(identity.canonical_agent_id))
                       OR EXISTS (
                           SELECT 1
                           FROM agent_identity_alias identity_alias
                           WHERE identity_alias.registry_id = identity.id
                             AND identity_alias.canonical_agent_id = identity.canonical_agent_id
                             AND identity_alias.alias_value = binding.agent_id
                             AND identity_alias.alias_type = 'LEGACY_AGENT_ID'
                             AND identity_alias.alias_status = 'ACTIVE'
                             AND identity_alias.valid_to IS NULL
                             AND identity_alias.client_id = #{clientId}
                             AND identity_alias.owner_jiacn = #{tenantId}
                             AND identity_alias.tenant_id = #{tenantId}
                             AND CAST(identity_alias.canonical_agent_id AS BINARY) = CAST(identity.canonical_agent_id AS BINARY)
                             AND OCTET_LENGTH(identity_alias.canonical_agent_id) = OCTET_LENGTH(identity.canonical_agent_id)
                             AND CAST(identity_alias.alias_value AS BINARY) = CAST(binding.agent_id AS BINARY)
                             AND OCTET_LENGTH(identity_alias.alias_value) = OCTET_LENGTH(binding.agent_id)
                             AND CAST(identity_alias.alias_type AS BINARY) = CAST('LEGACY_AGENT_ID' AS BINARY)
                             AND OCTET_LENGTH(identity_alias.alias_type) = OCTET_LENGTH('LEGACY_AGENT_ID')
                             AND CAST(identity_alias.alias_status AS BINARY) = CAST('ACTIVE' AS BINARY)
                             AND OCTET_LENGTH(identity_alias.alias_status) = OCTET_LENGTH('ACTIVE')
                             AND CAST(identity_alias.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                             AND OCTET_LENGTH(identity_alias.client_id) = OCTET_LENGTH(#{clientId})
                             AND CAST(identity_alias.owner_jiacn AS BINARY) = CAST(#{tenantId} AS BINARY)
                             AND OCTET_LENGTH(identity_alias.owner_jiacn) = OCTET_LENGTH(#{tenantId})
                             AND CAST(identity_alias.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
                             AND OCTET_LENGTH(identity_alias.tenant_id) = OCTET_LENGTH(#{tenantId})
                       )
                   )
             )
            LEFT JOIN agent_scene_state state
              ON state.tenant_id = #{tenantId}
             AND state.client_id = #{clientId}
             AND state.scene_id = #{sceneId}
             AND state.agent_id = runtime.agent_id
             AND state.persona_code = runtime.persona_code
             AND CAST(state.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
             AND OCTET_LENGTH(state.tenant_id) = OCTET_LENGTH(#{tenantId})
             AND CAST(state.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
             AND OCTET_LENGTH(state.client_id) = OCTET_LENGTH(#{clientId})
             AND CAST(state.scene_id AS BINARY) = CAST(#{sceneId} AS BINARY)
             AND OCTET_LENGTH(state.scene_id) = OCTET_LENGTH(#{sceneId})
             AND CAST(state.agent_id AS BINARY) = CAST(runtime.agent_id AS BINARY)
             AND OCTET_LENGTH(state.agent_id) = OCTET_LENGTH(runtime.agent_id)
             AND CAST(state.persona_code AS BINARY) = CAST(runtime.persona_code AS BINARY)
             AND OCTET_LENGTH(state.persona_code) = OCTET_LENGTH(runtime.persona_code)
             AND (state.expires_at IS NULL OR state.expires_at > #{activeAt})
            ORDER BY CAST(runtime.agent_id AS BINARY)
            """)
    List<AgentSceneSnapshotRow> selectSnapshotRows(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId,
            @Param("activeAt") long activeAt);

    @Insert("""
            INSERT INTO agent_scene_state
                (scene_id, agent_id, persona_code, behavior, origin_region_id, target_region_id,
                 related_type, related_id, phase, state_version, started_at, expected_arrival_at,
                 expires_at, tenant_id, client_id, create_time, update_time)
            VALUES
                (#{sceneId}, #{state.agentId}, #{state.personaCode}, #{state.behavior},
                 #{state.originRegionId}, #{state.targetRegionId}, #{state.relatedType},
                 #{state.relatedId}, #{state.phase}, #{state.stateVersion}, #{state.startedAt},
                 #{state.expectedArrivalAt}, #{state.expiresAt}, #{tenantId}, #{clientId},
                 #{state.createTime}, #{state.updateTime})
            ON DUPLICATE KEY UPDATE
                persona_code = IF(VALUES(state_version) > state_version, VALUES(persona_code), persona_code),
                behavior = IF(VALUES(state_version) > state_version, VALUES(behavior), behavior),
                origin_region_id = IF(VALUES(state_version) > state_version,
                    VALUES(origin_region_id), origin_region_id),
                target_region_id = IF(VALUES(state_version) > state_version,
                    VALUES(target_region_id), target_region_id),
                related_type = IF(VALUES(state_version) > state_version, VALUES(related_type), related_type),
                related_id = IF(VALUES(state_version) > state_version, VALUES(related_id), related_id),
                phase = IF(VALUES(state_version) > state_version, VALUES(phase), phase),
                started_at = IF(VALUES(state_version) > state_version, VALUES(started_at), started_at),
                expected_arrival_at = IF(VALUES(state_version) > state_version,
                    VALUES(expected_arrival_at), expected_arrival_at),
                expires_at = IF(VALUES(state_version) > state_version, VALUES(expires_at), expires_at),
                update_time = IF(VALUES(state_version) > state_version, VALUES(update_time), update_time),
                state_version = IF(VALUES(state_version) > state_version,
                    VALUES(state_version), state_version)
            """)
    int upsertMonotonic(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId,
            @Param("state") AgentSceneStateEntity state);
}
