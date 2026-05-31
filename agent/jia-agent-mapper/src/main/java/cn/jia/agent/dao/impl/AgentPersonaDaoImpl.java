package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

@Named
public class AgentPersonaDaoImpl extends BaseDaoImpl<AgentPersonaMapper, AgentPersonaEntity> implements AgentPersonaDao {
    @Override
    public AgentPersonaEntity findByName(String name) {
        return baseMapper.selectOne(new LambdaQueryWrapper<AgentPersonaEntity>()
                .eq(AgentPersonaEntity::getName, name)
                .last("limit 1"));
    }
}
