package cn.jia.agent.dao.impl;

import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.dao.AgentPersonaDao;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.mapper.AgentPersonaMapper;
import cn.jia.agent.common.WaterMarginPersonaCatalog;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.LinkedHashMap;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Objects;

@Named
public class AgentPersonaDaoImpl implements AgentPersonaDao {
    @Inject
    private AgentPersonaMapper baseMapper;
    private AgentPersonaCatalogCache catalogCache = new AgentPersonaCatalogCache();

    @Inject
    public void setCatalogCache(AgentPersonaCatalogCache catalogCache) {
        this.catalogCache = Objects.requireNonNull(catalogCache, "catalogCache");
    }

    @Override
    public List<AgentPersonaEntity> findCatalogProjection(String tenantId, String clientId) {
        requireExactScope(tenantId, clientId);
        return baseMapper.selectCatalogProjection(tenantId, clientId);
    }

    @Override
    public AgentPersonaEntity findCatalogPersona(
            String tenantId, String clientId, String personaCode) {
        requireExactScope(tenantId, clientId);
        requireExact(personaCode, "personaCode");
        return baseMapper.selectCatalogPersona(tenantId, clientId, personaCode);
    }

    @Override
    public AgentPersonaEntity findCatalogPersonaByName(String tenantId, String clientId, String name) {
        requireExact(name, "name");
        // Scope/active filtering belongs to the catalog SQL, not a second per-row pass here.
        List<AgentPersonaEntity> privateRows = findCatalogProjection(tenantId, clientId);
        var byCode = new LinkedHashMap<String, AgentPersonaEntity>();
        for (var builtin : WaterMarginPersonaCatalog.publicPersonas()) byCode.put(builtin.getPersonaCode(), builtin);
        for (var row : privateRows) byCode.put(row.getPersonaCode(), row);
        var matches = byCode.values().stream().filter(row -> name.equals(row.getName())).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous persona name in requested scope");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    @Override
    public int insertPrivate(String tenantId, String clientId, AgentPersonaEntity entity) {
        requirePrivateWrite(tenantId, clientId, entity);
        if (entity.getId() != null) throw new IllegalArgumentException("New persona must not choose an existing ID");
        entity.init4Creation();
        int changed = baseMapper.insert(entity);
        invalidateIfChanged(changed > 0);
        return changed;
    }

    @Override
    public int updatePrivate(String tenantId, String clientId, AgentPersonaEntity entity) {
        requirePrivateWrite(tenantId, clientId, entity);
        var scope = privateRow(tenantId, clientId, entity.getId());
        entity.init4Update();
        int changed = baseMapper.update(entity, scope);
        invalidateIfChanged(changed > 0);
        return changed;
    }

    @Override
    public int deletePrivate(String tenantId, String clientId, Long id) {
        var scope = privateRow(tenantId, clientId, id);
        requireSynchronizationIfTransactional();
        int changed = baseMapper.delete(scope);
        invalidateIfChanged(changed > 0);
        return changed;
    }

    private void requirePrivateWrite(String tenantId, String clientId, AgentPersonaEntity entity) {
        requireExact(tenantId, "tenantId");
        if (clientId != null) requireExact(clientId, "clientId");
        if (entity == null || !tenantId.equals(entity.getTenantId())
                || !Objects.equals(clientId, entity.getClientId())) {
            throw new IllegalArgumentException("Explicit private persona scope must match its row");
        }
        requireExact(entity.getPersonaCode(), "personaCode");
        requireExact(entity.getName(), "name");
        requireSynchronizationIfTransactional();
    }

    private QueryWrapper<AgentPersonaEntity> privateRow(String tenantId, String clientId, Long id) {
        requireExact(tenantId, "tenantId");
        if (clientId != null) requireExact(clientId, "clientId");
        if (id == null || id <= 0) throw new IllegalArgumentException("Persona ID is required");
        var query = new QueryWrapper<AgentPersonaEntity>().eq("id", id).eq("tenant_id", tenantId)
                .apply("CAST(tenant_id AS BINARY) = CAST({0} AS BINARY)", tenantId)
                .apply("OCTET_LENGTH(tenant_id) = OCTET_LENGTH({0})", tenantId);
        if (clientId == null) return query.isNull("client_id");
        return query.eq("client_id", clientId)
                .apply("CAST(client_id AS BINARY) = CAST({0} AS BINARY)", clientId)
                .apply("OCTET_LENGTH(client_id) = OCTET_LENGTH({0})", clientId);
    }

    private void requireSynchronizationIfTransactional() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Persona mutation requires transaction synchronization");
        }
    }

    private void invalidateIfChanged(boolean changed) {
        if (changed) {
            catalogCache.invalidateAllAfterCommit();
        }
    }

    private void requireExactScope(String tenantId, String clientId) {
        requireExact(tenantId, "tenantId");
        requireExact(clientId, "clientId");
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
