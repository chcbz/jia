package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Exact-scope, compare-and-set persistence for Runtime v1 installations. */
public interface AgentRuntimeV1InstallationMapper extends BaseMapper<AgentRuntimeV1InstallationEntity> {
    @Select("""
            SELECT * FROM agent_runtime_v1_installation
             WHERE installation_id=#{installationId}
               AND CAST(installation_id AS BINARY)=CAST(#{installationId} AS BINARY)
               AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
             LIMIT 1 FOR UPDATE
            """)
    AgentRuntimeV1InstallationEntity selectByInstallationForUpdate(@Param("installationId") String installationId);

    @Select("""
            SELECT * FROM agent_runtime_v1_installation
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND installation_id=#{installationId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(installation_id AS BINARY)=CAST(#{installationId} AS BINARY)
               AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})
             LIMIT 1
            """)
    AgentRuntimeV1InstallationEntity selectByInstallationInScope(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("installationId") String installationId);

    @Select("""
            SELECT * FROM agent_runtime_v1_installation
             WHERE runtime_authorization_hash=#{authorizationHash}
               AND runtime_authorization_hash IS NOT NULL
               AND status='ACTIVE'
               AND CAST(status AS BINARY)=CAST('ACTIVE' AS BINARY)
               AND OCTET_LENGTH(status)=OCTET_LENGTH('ACTIVE')
             LIMIT 1
            """)
    AgentRuntimeV1InstallationEntity selectActiveByAuthorizationHash(@Param("authorizationHash") byte[] authorizationHash);

    @Update("""
            UPDATE agent_runtime_v1_installation
               SET enrollment_consumed_at=#{now}, runtime_authorization_hash=#{authorizationHash},
                   runtime_authorization_issued_at=#{now}, status='ACTIVE', version=version+1,
                   update_time=#{now}
             WHERE id=#{id} AND version=#{expectedVersion}
               AND status='PENDING' AND enrollment_consumed_at IS NULL
               AND enrollment_expires_at>#{now}
               AND CAST(status AS BINARY)=CAST('PENDING' AS BINARY)
               AND OCTET_LENGTH(status)=OCTET_LENGTH('PENDING')
            """)
    int activateEnrollment(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
            @Param("authorizationHash") byte[] authorizationHash, @Param("now") long now);

    @Update("""
            UPDATE agent_runtime_v1_installation
               SET last_heartbeat_at=#{now}, version=version+1, update_time=#{now}
             WHERE id=#{id} AND version=#{expectedVersion} AND status='ACTIVE'
               AND CAST(status AS BINARY)=CAST('ACTIVE' AS BINARY)
               AND OCTET_LENGTH(status)=OCTET_LENGTH('ACTIVE')
            """)
    int heartbeat(@Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("now") long now);

    @Update("""
            UPDATE agent_runtime_v1_installation
               SET status='REVOKED', runtime_authorization_hash=NULL, version=version+1, update_time=#{now}
             WHERE id=#{id} AND version=#{expectedVersion}
               AND status IN ('PENDING','ACTIVE','REBINDS_REQUIRED')
               AND (CAST(status AS BINARY)=CAST('PENDING' AS BINARY)
                    OR CAST(status AS BINARY)=CAST('ACTIVE' AS BINARY)
                    OR CAST(status AS BINARY)=CAST('REBINDS_REQUIRED' AS BINARY))
            """)
    int revoke(@Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("now") long now);
}
