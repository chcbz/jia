package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentPersonaBindingDao extends IBaseDao<AgentPersonaBindingEntity> {
    AgentPersonaBindingEntity findActiveByClientAndPersona(String clientId, String personaCode);

    AgentPersonaBindingEntity findActiveByClientAndAgentId(String clientId, String agentId);

    AgentPersonaBindingEntity findActiveByClientJiacnAndPersona(String clientId, String jiacn, String personaCode);

    AgentPersonaBindingEntity findActiveByClientJiacnAndAgentId(String clientId, String jiacn, String agentId);

    List<AgentPersonaBindingEntity> findActiveByClientJiacn(String clientId, String jiacn);
}
