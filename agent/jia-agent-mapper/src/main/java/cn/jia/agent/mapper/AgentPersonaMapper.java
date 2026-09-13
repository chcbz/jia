package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentPersonaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentPersonaMapper extends BaseMapper<AgentPersonaEntity> {
    @Select("""
            SELECT persona_code, rank_no, star_name, name, title, avatar, visual_config,
                   abilities, power, intelligence, leadership, system_agent
            FROM agent_persona
            """)
    List<AgentPersonaEntity> selectRuntimeProjection();

    @Select("""
            SELECT persona_code, rank_no, star_name, name, title, avatar, visual_config,
                   abilities, power, intelligence, leadership, active, system_agent,
                   tenant_id, client_id
            FROM agent_persona
            WHERE active = 1
              AND (
                    (tenant_id = '0'
                     AND CAST(tenant_id AS BINARY) = CAST('0' AS BINARY)
                     AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH('0'))
                    OR
                    (tenant_id = #{tenantId}
                     AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
                     AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId}))
                  )
              AND (
                    client_id IS NULL
                    OR
                    (client_id = #{clientId}
                     AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                     AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId}))
                  )
            ORDER BY rank_no ASC, persona_code ASC
            """)
    List<AgentPersonaEntity> selectCatalogProjection(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId);
}
