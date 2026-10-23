package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentExternalServicePolicyEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AgentExternalServicePolicyMapper extends BaseMapper<AgentExternalServicePolicyEntity> {
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND agent_id=#{agent} AND binding_id=#{binding}
        ORDER BY revision DESC LIMIT 1 FOR UPDATE
        """)
    AgentExternalServicePolicyEntity latestForUpdate(@Param("tenant") String tenant, @Param("client") String client,
            @Param("agent") String agent, @Param("binding") long binding);
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND provider_owner_jiacn=#{owner}
          AND idempotency_key=#{key} FOR UPDATE
        """)
    AgentExternalServicePolicyEntity receiptForUpdate(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("key") String key);
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND agent_id=#{agent} AND binding_id=#{binding}
        ORDER BY revision DESC LIMIT 1
        """)
    AgentExternalServicePolicyEntity latest(@Param("tenant") String tenant, @Param("client") String client,
            @Param("agent") String agent, @Param("binding") long binding);
    @Select("""
        SELECT * FROM agent_external_service_policy
        WHERE tenant_id=#{tenant} AND client_id=#{client} AND provider_owner_jiacn=#{owner}
          AND idempotency_key=#{key}
        """)
    AgentExternalServicePolicyEntity receipt(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("key") String key);
    @Select("""
        SELECT * FROM agent_identity_registry
        WHERE BINARY tenant_id=BINARY #{tenant} AND BINARY client_id=BINARY #{client}
          AND BINARY canonical_agent_id=BINARY #{agent} AND canonical_type != 'SYSTEM'
        """)
    AgentIdentityRegistryEntity identity(@Param("tenant") String tenant, @Param("client") String client,
            @Param("agent") String agent);
    @Select("""
        SELECT i.* FROM agent_identity_registry i
        JOIN agent_persona_binding b ON b.id=i.binding_id
          AND BINARY b.client_id=BINARY i.client_id AND BINARY b.jiacn=BINARY i.owner_jiacn
          AND (b.tenant_id IS NULL OR BINARY b.tenant_id=BINARY i.tenant_id)
          AND b.status=1 AND b.bound_at>0 AND b.persona_code IS NOT NULL AND b.persona_code!=''
          AND (BINARY b.agent_id=BINARY i.canonical_agent_id OR EXISTS (
            SELECT 1 FROM agent_identity_alias a WHERE a.registry_id=i.id
              AND BINARY a.tenant_id=BINARY i.tenant_id AND BINARY a.client_id=BINARY i.client_id
              AND BINARY a.owner_jiacn=BINARY i.owner_jiacn AND BINARY a.alias_value=BINARY b.agent_id
              AND BINARY a.canonical_agent_id=BINARY i.canonical_agent_id
              AND a.alias_type='LEGACY_AGENT_ID' AND a.alias_status='ACTIVE'
              AND a.valid_from>0 AND a.valid_to IS NULL AND a.audit_reason IS NOT NULL AND a.audit_reason!=''))
        WHERE BINARY i.tenant_id=BINARY #{tenant} AND BINARY i.client_id=BINARY #{client}
          AND i.lifecycle_status='ACTIVE' AND i.canonical_type != 'SYSTEM' AND i.binding_id IS NOT NULL
          AND BINARY i.canonical_agent_id > BINARY #{after}
          AND (BINARY i.owner_jiacn=BINARY #{owner} OR EXISTS (
            SELECT 1 FROM agent_external_service_policy p
            WHERE p.tenant_id=i.tenant_id AND p.client_id=i.client_id
              AND p.agent_id=i.canonical_agent_id AND p.binding_id=i.binding_id AND p.enabled=1
              AND p.revision=(SELECT MAX(p2.revision) FROM agent_external_service_policy p2
                WHERE p2.tenant_id=p.tenant_id AND p2.client_id=p.client_id
                  AND p2.agent_id=p.agent_id AND p2.binding_id=p.binding_id)))
        ORDER BY BINARY i.canonical_agent_id LIMIT #{limit}
        """)
    List<AgentIdentityRegistryEntity> candidates(@Param("tenant") String tenant, @Param("client") String client,
            @Param("owner") String owner, @Param("after") String after, @Param("limit") int limit);
}
