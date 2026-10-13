package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AgentPersonaDao extends IBaseDao<AgentPersonaEntity> {
    AgentPersonaEntity findByName(String name);

    AgentPersonaEntity findByCode(String personaCode);

    AgentPersonaEntity findByNameInReadScope(
            String tenantId, String clientId, String name);

    AgentPersonaEntity findByCodeInReadScope(
            String tenantId, String clientId, String personaCode);

    List<AgentPersonaEntity> findRuntimeProjection(String tenantId, String clientId);

    List<AgentPersonaEntity> findCatalogProjection(String tenantId, String clientId);
}
