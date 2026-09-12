package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentPersonaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentPersonaMapper extends BaseMapper<AgentPersonaEntity> {
    @Select("""
            SELECT persona_code, rank_no, star_name, name, title, avatar, visual_config,
                   abilities, power, intelligence, leadership, system_agent
            FROM agent_persona
            """)
    List<AgentPersonaEntity> selectRuntimeProjection();
}
