package cn.jia.agent.dao.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentPersonaBindingDaoImpl extends BaseDaoImpl<AgentPersonaBindingMapper, AgentPersonaBindingEntity>
        implements AgentPersonaBindingDao {
    @Override
    public AgentPersonaBindingEntity findActiveByClientAndPersona(String clientId, String personaCode) {
        return baseMapper.selectOne(activeWrapper(clientId)
                .eq(AgentPersonaBindingEntity::getPersonaCode, personaCode)
                .last("limit 1"));
    }

    @Override
    public AgentPersonaBindingEntity findActiveByClientAndAgentId(String clientId, String agentId) {
        return baseMapper.selectOne(activeWrapper(clientId)
                .eq(AgentPersonaBindingEntity::getAgentId, agentId)
                .last("limit 1"));
    }

    @Override
    public AgentPersonaBindingEntity findActiveByClientJiacnAndPersona(String clientId, String jiacn, String personaCode) {
        return baseMapper.selectOne(activeWrapper(clientId)
                .eq(AgentPersonaBindingEntity::getJiacn, jiacn)
                .eq(AgentPersonaBindingEntity::getPersonaCode, personaCode)
                .last("limit 1"));
    }

    @Override
    public AgentPersonaBindingEntity findActiveByClientJiacnAndAgentId(String clientId, String jiacn, String agentId) {
        return baseMapper.selectOne(activeWrapper(clientId)
                .eq(AgentPersonaBindingEntity::getJiacn, jiacn)
                .eq(AgentPersonaBindingEntity::getAgentId, agentId)
                .last("limit 1"));
    }

    @Override
    public List<AgentPersonaBindingEntity> findActiveByClientJiacn(String clientId, String jiacn) {
        return baseMapper.selectList(activeWrapper(clientId)
                .eq(AgentPersonaBindingEntity::getJiacn, jiacn)
                .orderByAsc(AgentPersonaBindingEntity::getPersonaCode));
    }

    private LambdaQueryWrapper<AgentPersonaBindingEntity> activeWrapper(String clientId) {
        return new LambdaQueryWrapper<AgentPersonaBindingEntity>()
                .eq(AgentPersonaBindingEntity::getClientId, clientId)
                .eq(AgentPersonaBindingEntity::getStatus, AgentConstants.BINDING_STATUS_ACTIVE);
    }
}
