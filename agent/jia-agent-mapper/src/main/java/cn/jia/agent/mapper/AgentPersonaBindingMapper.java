package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentPersonaBindingMapper extends BaseMapper<AgentPersonaBindingEntity> {
    @Select("SELECT * FROM agent_persona_binding WHERE id = #{id} FOR UPDATE")
    AgentPersonaBindingEntity selectByIdForUpdate(@Param("id") long id);
}
