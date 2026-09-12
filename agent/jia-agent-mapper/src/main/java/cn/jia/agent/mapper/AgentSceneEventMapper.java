package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentSceneEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentSceneEventMapper extends BaseMapper<AgentSceneEventEntity> {
    @Insert("""
            INSERT INTO agent_scene_version
                (tenant_id, client_id, scene_id, current_version, create_time, update_time)
            VALUES
                (#{tenantId}, #{clientId}, #{sceneId}, 0, #{now}, #{now})
            ON DUPLICATE KEY UPDATE
                current_version = current_version,
                update_time = update_time
            """)
    int ensureAndLockVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId,
            @Param("now") long now);

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
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(scene_id AS BINARY) = CAST(#{sceneId} AS BINARY)
              AND OCTET_LENGTH(scene_id) = OCTET_LENGTH(#{sceneId})
            LIMIT 1
            """)
    Long selectCurrentVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId);

    @Select("""
            SELECT MIN(scene_version)
            FROM agent_scene_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND scene_id = #{sceneId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(scene_id AS BINARY) = CAST(#{sceneId} AS BINARY)
              AND OCTET_LENGTH(scene_id) = OCTET_LENGTH(#{sceneId})
            """)
    Long selectEarliestVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId);

    @Select("""
            SELECT MAX(scene_version)
            FROM agent_scene_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND scene_id = #{sceneId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(scene_id AS BINARY) = CAST(#{sceneId} AS BINARY)
              AND OCTET_LENGTH(scene_id) = OCTET_LENGTH(#{sceneId})
            """)
    Long selectLatestVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId);

    @Select("""
            SELECT scene_version, event_type, event_json, occurred_at
            FROM agent_scene_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND scene_id = #{sceneId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(scene_id AS BINARY) = CAST(#{sceneId} AS BINARY)
              AND OCTET_LENGTH(scene_id) = OCTET_LENGTH(#{sceneId})
              AND scene_version > #{sinceVersion}
            ORDER BY scene_version ASC
            LIMIT #{limit}
            """)
    List<AgentSceneEventEntity> selectAfterVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sceneId") String sceneId,
            @Param("sinceVersion") long sinceVersion,
            @Param("limit") int limit);
}
