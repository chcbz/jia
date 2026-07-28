package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AgentIdentityRegistryMapper extends BaseMapper<AgentIdentityRegistryEntity> {
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
