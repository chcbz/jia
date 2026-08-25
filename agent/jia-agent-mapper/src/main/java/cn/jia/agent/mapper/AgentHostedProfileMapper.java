package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentHostedProfileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentHostedProfileMapper extends BaseMapper<AgentHostedProfileEntity> {
    @Select("""
            SELECT * FROM agent_hosted_profile
            WHERE binding_id = #{bindingId}
              AND tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND owner_jiacn = #{ownerJiacn}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            LIMIT 1
            """)
    AgentHostedProfileEntity findExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("bindingId") long bindingId);

    @Select("""
            SELECT * FROM agent_hosted_profile
            WHERE binding_id = #{bindingId}
              AND tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND owner_jiacn = #{ownerJiacn}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            LIMIT 1 FOR UPDATE
            """)
    AgentHostedProfileEntity findExactForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("bindingId") long bindingId);

    @Update("""
            UPDATE agent_hosted_profile
               SET lifecycle_state=#{nextState}, resume_state=NULL, generation=#{nextGeneration},
                   desired_enabled=#{desiredEnabled}, last_error=NULL, update_time=#{now}
             WHERE id=#{id}
               AND lifecycle_state=#{expectedState}
               AND CAST(lifecycle_state AS BINARY)=CAST(#{expectedState} AS BINARY)
               AND OCTET_LENGTH(lifecycle_state)=OCTET_LENGTH(#{expectedState})
               AND generation=#{expectedGeneration}
            """)
    int transition(@Param("id") long id, @Param("expectedState") String expectedState,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("nextState") String nextState, @Param("nextGeneration") long nextGeneration,
            @Param("desiredEnabled") boolean desiredEnabled, @Param("now") long now);

    @Update("""
            UPDATE agent_hosted_profile
               SET lifecycle_state='REPAIR_REQUIRED', resume_state=#{resumeState},
                   last_error=#{lastError}, update_time=#{now}
             WHERE id=#{id}
               AND lifecycle_state NOT IN ('SUSPENDED','REPAIR_REQUIRED')
               AND CAST(lifecycle_state AS BINARY) NOT IN (CAST('SUSPENDED' AS BINARY), CAST('REPAIR_REQUIRED' AS BINARY))
            """)
    int markRepair(@Param("id") long id, @Param("resumeState") String resumeState,
            @Param("lastError") String lastError, @Param("now") long now);
}
