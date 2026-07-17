package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentSceneStateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface AgentSceneStateMapper extends BaseMapper<AgentSceneStateEntity> {
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
