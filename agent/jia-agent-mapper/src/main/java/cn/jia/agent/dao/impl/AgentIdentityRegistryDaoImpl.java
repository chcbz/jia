package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.inject.Named;

@Named
public class AgentIdentityRegistryDaoImpl
        extends BaseDaoImpl<AgentIdentityRegistryMapper, AgentIdentityRegistryEntity>
        implements AgentIdentityRegistryDao {
    @Override
    public AgentIdentityRegistryEntity findExactByCanonicalInScope(String tenantId, String clientId,
            String ownerJiacn, String canonicalAgentId) {
        requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireExact(canonicalAgentId, "canonicalAgentId", 400);
        QueryWrapper<AgentIdentityRegistryEntity> query = new QueryWrapper<>();
        IdentityExactQuerySupport.exact(query, "tenant_id", tenantId, 200);
        IdentityExactQuerySupport.exact(query, "client_id", clientId, 200);
        IdentityExactQuerySupport.exact(query, "owner_jiacn", ownerJiacn, 200);
        IdentityExactQuerySupport.exact(query, "canonical_agent_id", canonicalAgentId, 400);
        return baseMapper.selectOne(query.last("limit 1"));
    }

    @Override
    public AgentIdentityRegistryEntity findExactByCanonicalInScopeForUpdate(
            String tenantId, String clientId, String ownerJiacn, String canonicalAgentId) {
        requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireExact(canonicalAgentId, "canonicalAgentId", 400);
        QueryWrapper<AgentIdentityRegistryEntity> query = new QueryWrapper<>();
        IdentityExactQuerySupport.exact(query, "tenant_id", tenantId, 200);
        IdentityExactQuerySupport.exact(query, "client_id", clientId, 200);
        IdentityExactQuerySupport.exact(query, "owner_jiacn", ownerJiacn, 200);
        IdentityExactQuerySupport.exact(query, "canonical_agent_id", canonicalAgentId, 400);
        return baseMapper.selectOne(query.last("limit 1 FOR UPDATE"));
    }

    @Override
    public AgentIdentityRegistryEntity findExactByBindingInScope(String tenantId, String clientId,
            String ownerJiacn, long bindingId) {
        requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireBindingId(bindingId);
        QueryWrapper<AgentIdentityRegistryEntity> query = new QueryWrapper<>();
        IdentityExactQuerySupport.exact(query, "tenant_id", tenantId, 200);
        IdentityExactQuerySupport.exact(query, "client_id", clientId, 200);
        IdentityExactQuerySupport.exact(query, "owner_jiacn", ownerJiacn, 200);
        query.eq("binding_id", bindingId);
        return baseMapper.selectOne(query.last("limit 1"));
    }

    @Override
    public AgentIdentityRegistryEntity findExactByBindingInScopeForUpdate(
            String tenantId, String clientId, String ownerJiacn, long bindingId) {
        requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireBindingId(bindingId);
        QueryWrapper<AgentIdentityRegistryEntity> query = new QueryWrapper<>();
        IdentityExactQuerySupport.exact(query, "tenant_id", tenantId, 200);
        IdentityExactQuerySupport.exact(query, "client_id", clientId, 200);
        IdentityExactQuerySupport.exact(query, "owner_jiacn", ownerJiacn, 200);
        query.eq("binding_id", bindingId);
        return baseMapper.selectOne(query.last("limit 1 FOR UPDATE"));
    }

    private static void requireStrictOwnerScope(
            String tenantId, String clientId, String ownerJiacn) {
        requireExact(tenantId, "tenantId", 200);
        requireExact(clientId, "clientId", 200);
        requireExact(ownerJiacn, "ownerJiacn", 200);
        if (!"0".equals(tenantId) || "0".equals(ownerJiacn)) {
            throw new IllegalArgumentException("identity scope requires tenant 0 and a real owner");
        }
    }

    private static void requireBindingId(long bindingId) {
        if (bindingId <= 0) {
            throw new IllegalArgumentException("bindingId is invalid");
        }
    }

    private static void requireExact(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    @Override
    public int activateProvisioned(long id, long activatedAt) {
        return baseMapper.activateProvisioned(id, activatedAt);
    }

    @Override
    public int suspendUsable(long id, long suspendedAt) {
        return baseMapper.suspendUsable(id, suspendedAt);
    }
}
