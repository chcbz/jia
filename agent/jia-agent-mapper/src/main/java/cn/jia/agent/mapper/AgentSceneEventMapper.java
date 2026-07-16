package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentSceneEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentSceneEventMapper extends BaseMapper<AgentSceneEventEntity> {
    @Insert("""
            INSERT INTO agent_scene_version
                (tenant_id, client_id, scene_id, current_version, create_time, update_time)
            VALUES
                (#{tenantId}, #{clientId}, #{sceneId}, LAST_INSERT_ID(1), #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                current_version = LAST_INSERT_ID(current_version + 1),
                update_time = VALUES(update_time)
            """)
    int allocateNextVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId,
            @Param("now") long now);

    @Select("SELECT LAST_INSERT_ID()")
    Long selectLastAllocatedVersion();

    @Select("""
            SELECT current_version
            FROM agent_scene_version
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND scene_id = #{sceneId}
            LIMIT 1
            """)
    Long selectCurrentVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId);
}
