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
        QueryWrapper<AgentIdentityAliasEntity> query = new QueryWrapper<>();
        IdentityExactQuerySupport.exact(query, "tenant_id", tenantId, 200);
        IdentityExactQuerySupport.exact(query, "client_id", clientId, 200);
        IdentityExactQuerySupport.exact(query, "owner_jiacn", ownerJiacn, 200);
        IdentityExactQuerySupport.exact(query, "alias_type", AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID, 128);
        IdentityExactQuerySupport.exact(query, "alias_status", AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE, 80);
        IdentityExactQuerySupport.exact(query, "alias_value", aliasValue, 400);
        query.isNull("valid_to");
        return baseMapper.selectOne(query.last("limit 1"));
    }
}
