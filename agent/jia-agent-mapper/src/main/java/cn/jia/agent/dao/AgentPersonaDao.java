package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.core.dao.IBaseDao;

public interface AgentPersonaDao extends IBaseDao<AgentPersonaEntity> {
    AgentPersonaEntity findByName(String name);
}
