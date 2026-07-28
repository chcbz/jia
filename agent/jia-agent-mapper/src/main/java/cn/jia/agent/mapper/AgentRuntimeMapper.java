package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
