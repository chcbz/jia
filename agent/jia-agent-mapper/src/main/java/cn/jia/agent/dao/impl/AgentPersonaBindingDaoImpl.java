package cn.jia.agent.dao.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentPersonaCatalogBindingRow;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentPersonaBindingDaoImpl extends BaseDaoImpl<AgentPersonaBindingMapper, AgentPersonaBindingEntity>
        implements AgentPersonaBindingDao {
    @Override
    public AgentPersonaBindingEntity findByIdForUpdate(long id) {
        return baseMapper.selectByIdForUpdate(id);
    }

    @Override
    public AgentPersonaBindingEntity findActiveByClientAndPersona(String clientId, String personaCode) {
        return baseMapper.selectOne(activeWrapper(clientId)
                .eq(AgentPersonaBindingEntity::getPersonaCode, personaCode)
                .last("limit 1"));
    }


    @Override
    public AgentPersonaBindingEntity findExactActiveByScopeAndPersona(
            String tenantId, String clientId, String ownerJiacn, String personaCode) {
        requirePersonaScope(tenantId, clientId, ownerJiacn, personaCode);
        return baseMapper.findExactActiveByScopeAndPersona(
                tenantId, clientId, ownerJiacn, personaCode, AgentConstants.BINDING_STATUS_ACTIVE);
    }

    @Override
    public List<AgentPersonaCatalogBindingRow> findCatalogOverlay(
            String tenantId, String clientId, String ownerJiacn) {
        requirePersonaOwnerScope(tenantId, clientId, ownerJiacn);
        return baseMapper.findCatalogOverlay(
                tenantId, clientId, ownerJiacn, AgentConstants.BINDING_STATUS_ACTIVE);
    }

    @Override
    public AgentPersonaBindingEntity findExactActiveByScopeAndPersonaForUpdate(
            String tenantId, String clientId, String ownerJiacn, String personaCode) {
        requirePersonaScope(tenantId, clientId, ownerJiacn, personaCode);
        return baseMapper.findExactActiveByScopeAndPersonaForUpdate(
                tenantId, clientId, ownerJiacn, personaCode, AgentConstants.BINDING_STATUS_ACTIVE);
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
        requireExact(clientId, "clientId", 50);
        requireExact(jiacn, "jiacn", 50);
        requireExact(agentId, "agentId", 100);
        return baseMapper.findExactActiveByOwner(
                clientId, jiacn, agentId, AgentConstants.BINDING_STATUS_ACTIVE);
    }

    @Override
    public AgentPersonaBindingEntity findActiveByClientJiacnAndAgentIdForUpdate(
            String clientId, String jiacn, String agentId) {
        requireExact(clientId, "clientId", 50);
        requireExact(jiacn, "jiacn", 50);
        requireExact(agentId, "agentId", 100);
        return baseMapper.findExactActiveByOwnerForUpdate(
                clientId, jiacn, agentId, AgentConstants.BINDING_STATUS_ACTIVE);
    }

    @Override
    public List<AgentPersonaBindingEntity> findActiveByClientJiacn(String clientId, String jiacn) {
        return baseMapper.selectList(activeWrapper(clientId)
                .eq(AgentPersonaBindingEntity::getJiacn, jiacn)
                .orderByAsc(AgentPersonaBindingEntity::getPersonaCode));
    }

    private void requirePersonaScope(String tenantId, String clientId,
            String ownerJiacn, String personaCode) {
        requireExact(tenantId, "tenantId", 50);
        requireExact(clientId, "clientId", 50);
        requireExact(ownerJiacn, "ownerJiacn", 50);
        requireExact(personaCode, "personaCode", 50);
        requirePersonaOwnerScope(tenantId, clientId, ownerJiacn);
    }

    private void requirePersonaOwnerScope(String tenantId, String clientId, String ownerJiacn) {
        requireExact(tenantId, "tenantId", 50);
        requireExact(clientId, "clientId", 50);
        requireExact(ownerJiacn, "ownerJiacn", 50);
        if ("0".equals(ownerJiacn) || !tenantId.equals(ownerJiacn)) {
            throw new IllegalArgumentException("persona owner scope is invalid");
        }
    }

    private void requireExact(String value, String field, int maxCodePoints) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > maxCodePoints
                || hasUnpairedSurrogate(value)
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().noneMatch(codePoint -> !isPadding(codePoint))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private LambdaQueryWrapper<AgentPersonaBindingEntity> activeWrapper(String clientId) {
        return new LambdaQueryWrapper<AgentPersonaBindingEntity>()
                .eq(AgentPersonaBindingEntity::getClientId, clientId)
                .eq(AgentPersonaBindingEntity::getStatus, AgentConstants.BINDING_STATUS_ACTIVE);
    }
}
