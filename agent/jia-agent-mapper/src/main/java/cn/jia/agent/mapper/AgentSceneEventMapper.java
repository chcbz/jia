package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentSceneEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentSceneEventMapper extends BaseMapper<AgentSceneEventEntity> {
    @Select("""
            SELECT scene_version
            FROM agent_scene_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND scene_id = #{sceneId}
            ORDER BY scene_version DESC
            LIMIT 1 FOR UPDATE
            """)
    Long selectLatestVersionForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId);
}
