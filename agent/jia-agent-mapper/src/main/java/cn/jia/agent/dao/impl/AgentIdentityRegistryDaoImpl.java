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
        QueryWrapper<AgentIdentityRegistryEntity> query = new QueryWrapper<>();
        IdentityExactQuerySupport.exact(query, "tenant_id", tenantId, 200);
        IdentityExactQuerySupport.exact(query, "client_id", clientId, 200);
        IdentityExactQuerySupport.exact(query, "owner_jiacn", ownerJiacn, 200);
        query.eq("binding_id", bindingId);
        return baseMapper.selectOne(query.last("limit 1 FOR UPDATE"));
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
