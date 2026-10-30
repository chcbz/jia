package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentPersonaEntity;
import java.util.List;

/** Scoped private overlays; platform definitions are immutable and have no CRUD entry here. */
public interface AgentPersonaDao {
    List<AgentPersonaEntity> findCatalogProjection(String tenantId, String clientId);
    AgentPersonaEntity findCatalogPersona(String tenantId, String clientId, String personaCode);
    AgentPersonaEntity findCatalogPersonaByName(String tenantId, String clientId, String name);
    int insertPrivate(String tenantId, String clientId, AgentPersonaEntity entity);
    int updatePrivate(String tenantId, String clientId, AgentPersonaEntity entity);
    int deletePrivate(String tenantId, String clientId, Long id);
}
