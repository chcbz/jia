package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentPersonaDao extends IBaseDao<AgentPersonaEntity> {
    AgentPersonaEntity findByName(String name);

    AgentPersonaEntity findByCode(String personaCode);

    List<AgentPersonaEntity> findRuntimeProjection();
}
