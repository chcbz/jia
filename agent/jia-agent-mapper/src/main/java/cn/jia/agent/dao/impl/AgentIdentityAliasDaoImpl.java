package cn.jia.agent.dao.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.inject.Named;

@Named
public class AgentIdentityAliasDaoImpl
        extends BaseDaoImpl<AgentIdentityAliasMapper, AgentIdentityAliasEntity>
        implements AgentIdentityAliasDao {
    @Override
    public AgentIdentityAliasEntity findExactActiveLegacyAlias(String tenantId, String clientId,
            String ownerJiacn, String aliasValue) {
        QueryWrapper<AgentIdentityAliasEntity> query = exactAliasQuery(
                tenantId, clientId, ownerJiacn, aliasValue);
        IdentityExactQuerySupport.exact(query, "alias_status",
                AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE, 80);
        query.isNull("valid_to");
        return baseMapper.selectOne(query.last("limit 1"));
    }

    @Override
    public AgentIdentityAliasEntity findExactLegacyAlias(String tenantId, String clientId,
            String ownerJiacn, String aliasValue) {
        QueryWrapper<AgentIdentityAliasEntity> query = exactAliasQuery(
                tenantId, clientId, ownerJiacn, aliasValue);
        query.in("alias_status",
                AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE,
                AgentConstants.IDENTITY_ALIAS_STATUS_REVOKED);
        query.orderByDesc("valid_from").orderByDesc("id");
        return baseMapper.selectOne(query.last("limit 1"));
    }

    private QueryWrapper<AgentIdentityAliasEntity> exactAliasQuery(
            String tenantId, String clientId, String ownerJiacn, String aliasValue) {
        requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireExact(aliasValue, "aliasValue", 400);
        QueryWrapper<AgentIdentityAliasEntity> query = new QueryWrapper<>();
        IdentityExactQuerySupport.exact(query, "tenant_id", tenantId, 200);
        IdentityExactQuerySupport.exact(query, "client_id", clientId, 200);
        IdentityExactQuerySupport.exact(query, "owner_jiacn", ownerJiacn, 200);
        IdentityExactQuerySupport.exact(query, "alias_type",
                AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID, 128);
        IdentityExactQuerySupport.exact(query, "alias_value", aliasValue, 400);
        return query;
    }

    private static void requireStrictOwnerScope(
            String tenantId, String clientId, String ownerJiacn) {
        requireExact(tenantId, "tenantId", 200);
        requireExact(clientId, "clientId", 200);
        requireExact(ownerJiacn, "ownerJiacn", 200);
        if (!"0".equals(tenantId) || "0".equals(ownerJiacn)) {
            throw new IllegalArgumentException("identity alias scope requires tenant 0 and a real owner");
        }
    }

    private static void requireExact(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

}
