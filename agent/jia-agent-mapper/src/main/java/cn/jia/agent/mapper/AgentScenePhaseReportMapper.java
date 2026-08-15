package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentScenePhaseReportMapper extends BaseMapper<AgentScenePhaseReportEntity> {
    @Insert("""
            INSERT IGNORE INTO agent_scene_phase_report (
              tenant_id, client_id, scene_id, report_id, agent_id,
              state_version, phase, region_id, result, occurred_at,
              processed_at, create_time, update_time
            ) VALUES (
              #{tenantId}, #{clientId}, #{sceneId}, #{reportId}, #{agentId},
              #{stateVersion}, #{phase}, #{regionId}, #{result}, #{occurredAt},
              #{processedAt}, #{createTime}, #{updateTime}
            )
            """)
    int reserveIgnore(AgentScenePhaseReportEntity entity);

    @Select("""
            SELECT *
            FROM agent_scene_phase_report
            WHERE (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND scene_id = #{sceneId}
              AND report_id = #{reportId}
            LIMIT 1
            FOR UPDATE
            """)
    AgentScenePhaseReportEntity selectScopedForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId,
            @Param("reportId") String reportId);
}
