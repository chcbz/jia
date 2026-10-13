package cn.jia.agent.dao.impl;

import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Named
public class AgentPersonaDaoImpl extends BaseDaoImpl<AgentPersonaMapper, AgentPersonaEntity> implements AgentPersonaDao {
    private AgentPersonaCatalogCache catalogCache = new AgentPersonaCatalogCache();

    @Inject
    public void setCatalogCache(AgentPersonaCatalogCache catalogCache) {
        this.catalogCache = Objects.requireNonNull(catalogCache, "catalogCache");
    }

    @Override
    public AgentPersonaEntity findByName(String name) {
        return baseMapper.selectOne(new LambdaQueryWrapper<AgentPersonaEntity>()
                .eq(AgentPersonaEntity::getName, name)
                .last("limit 1"));
    }

    @Override
    public AgentPersonaEntity findByCode(String personaCode) {
        return baseMapper.selectOne(new LambdaQueryWrapper<AgentPersonaEntity>()
                .eq(AgentPersonaEntity::getPersonaCode, personaCode)
                .last("limit 1"));
    }

    @Override
    public AgentPersonaEntity findByNameInReadScope(
            String tenantId, String clientId, String name) {
        requireExact(name, "name");
        return uniqueExact(findRuntimeProjection(tenantId, clientId), name, false);
    }

    @Override
    public AgentPersonaEntity findByCodeInReadScope(
            String tenantId, String clientId, String personaCode) {
        requireExact(personaCode, "personaCode");
        return uniqueExact(findRuntimeProjection(tenantId, clientId), personaCode, true);
    }

    @Override
    public List<AgentPersonaEntity> findRuntimeProjection(String tenantId, String clientId) {
        requireReadScope(tenantId, clientId);
        return baseMapper.selectRuntimeProjection(tenantId, clientId);
    }

    @Override
    public List<AgentPersonaEntity> findCatalogProjection(String tenantId, String clientId) {
        requireReadScope(tenantId, clientId);
        return baseMapper.selectCatalogProjection(tenantId, clientId);
    }

    @Override
    public int insert(AgentPersonaEntity entity) {
        int changed = super.insert(entity);
        invalidateIfChanged(changed > 0);
        return changed;
    }

    @Override
    public boolean insertBatch(Collection<AgentPersonaEntity> entityList, int batchSize) {
        boolean changed = super.insertBatch(entityList, batchSize);
        invalidateIfChanged(changed);
        return changed;
    }

    @Override
    public boolean insertOrUpdateBatch(Collection<AgentPersonaEntity> entityList, int batchSize) {
        boolean changed = super.insertOrUpdateBatch(entityList, batchSize);
        invalidateIfChanged(changed);
        return changed;
    }

    @Override
    public int deleteById(Serializable id) {
        int changed = super.deleteById(id);
        invalidateIfChanged(changed > 0);
        return changed;
    }

    @Override
    public int deleteBatchIds(Collection<?> idList) {
        int changed = super.deleteBatchIds(idList);
        invalidateIfChanged(changed > 0);
        return changed;
    }

    @Override
    public int updateById(AgentPersonaEntity entity) {
        int changed = super.updateById(entity);
        invalidateIfChanged(changed > 0);
        return changed;
    }

    @Override
    public boolean updateBatchById(Collection<AgentPersonaEntity> entityList, int batchSize) {
        boolean changed = super.updateBatchById(entityList, batchSize);
        invalidateIfChanged(changed);
        return changed;
    }

    private void invalidateIfChanged(boolean changed) {
        if (changed) {
            catalogCache.invalidateAllAfterCommit();
        }
    }

    private void requireReadScope(String tenantId, String clientId) {
        TenantId.of(tenantId);
        requireExact(tenantId, "tenantId");
        requireExact(clientId, "clientId");
        BusinessScope trusted = TrustedTenantContext.requireScope();
        if (!"cyf".equals(trusted.dataDomainId().value())
                || !tenantId.equals(trusted.tenantId().value())
                || !clientId.equals(trusted.callerClientId())) {
            throw new IllegalArgumentException("persona read scope is invalid");
        }
    }

    private AgentPersonaEntity uniqueExact(
            List<AgentPersonaEntity> rows, String expected, boolean byCode) {
        AgentPersonaEntity found = null;
        for (AgentPersonaEntity row : Objects.requireNonNull(rows, "persona projection")) {
            String actual = row == null ? null : byCode ? row.getPersonaCode() : row.getName();
            if (!expected.equals(actual)) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("persona read projection is ambiguous");
            }
            found = row;
        }
        return found;
    }

    private void requireExact(String value, String field) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 50
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().allMatch(this::isPadding)
                || value.codePoints().anyMatch(Character::isISOControl)
                || hasUnpairedSurrogate(value)) {
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
}
