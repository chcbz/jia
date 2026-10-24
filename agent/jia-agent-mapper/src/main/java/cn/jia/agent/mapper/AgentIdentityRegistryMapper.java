package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentIdentityRegistryMapper extends BaseMapper<AgentIdentityRegistryEntity> {
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM agent_identity_registry
             WHERE tenant_id=#{tenantId}
               AND client_id=#{clientId}
               AND canonical_agent_id=#{canonicalAgentId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(canonical_agent_id AS BINARY)=CAST(#{canonicalAgentId} AS BINARY)
               AND OCTET_LENGTH(canonical_agent_id)=OCTET_LENGTH(#{canonicalAgentId})
             LIMIT 2
            """)
    List<AgentIdentityRegistryEntity> selectBootstrapExactByCanonical(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("canonicalAgentId") String canonicalAgentId);

    @Update("""
            UPDATE agent_identity_registry
               SET lifecycle_status = 'ACTIVE',
                   activated_at = COALESCE(activated_at, #{activatedAt}),
                   update_time = #{activatedAt}
             WHERE id = #{id}
               AND lifecycle_status = 'PROVISIONED'
               AND CAST(lifecycle_status AS BINARY(80)) = CAST('PROVISIONED' AS BINARY(80))
               AND OCTET_LENGTH(lifecycle_status) = OCTET_LENGTH('PROVISIONED')
            """)
    int activateProvisioned(@Param("id") long id, @Param("activatedAt") long activatedAt);

    @Update("""
            UPDATE agent_identity_registry
               SET lifecycle_status = 'SUSPENDED',
                   suspended_at = #{suspendedAt},
                   update_time = #{suspendedAt}
             WHERE id = #{id}
               AND lifecycle_status IN ('PROVISIONED', 'ACTIVE')
               AND (
                    (CAST(lifecycle_status AS BINARY(80)) = CAST('PROVISIONED' AS BINARY(80))
                     AND OCTET_LENGTH(lifecycle_status) = OCTET_LENGTH('PROVISIONED'))
                 OR (CAST(lifecycle_status AS BINARY(80)) = CAST('ACTIVE' AS BINARY(80))
                     AND OCTET_LENGTH(lifecycle_status) = OCTET_LENGTH('ACTIVE'))
               )
            """)
    int suspendUsable(@Param("id") long id, @Param("suspendedAt") long suspendedAt);
}
