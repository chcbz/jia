package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentScenePhaseReportMapper extends BaseMapper<AgentScenePhaseReportEntity> {
    @Select("""
            SELECT *
            FROM agent_scene_phase_report
            WHERE tenant_id = #{tenantId}
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
