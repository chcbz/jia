package cn.jia.agent.output.mapper;

import cn.jia.agent.output.entity.OutputRunBindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OutputRunBindingMapper extends BaseMapper<OutputRunBindingEntity> {
    @Select("""
            <script>
            SELECT * FROM output_run_binding
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND run_id=#{runId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(run_id AS BINARY)=CAST(#{runId} AS BINARY)
              AND OCTET_LENGTH(run_id)=OCTET_LENGTH(#{runId})
            LIMIT 1
            <if test="forUpdate">FOR UPDATE</if>
            </script>
            """)
    OutputRunBindingEntity findExactByRun(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("runId") String runId,
            @Param("forUpdate") boolean forUpdate);

    @Select("""
            SELECT * FROM output_run_binding
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId}
              AND source_type=#{sourceType} AND source_id=#{sourceId}
              AND producer_agent_id=#{producerAgentId}
              AND origin_type=#{originType} AND origin_id=#{originId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(source_type AS BINARY)=CAST(#{sourceType} AS BINARY)
              AND OCTET_LENGTH(source_type)=OCTET_LENGTH(#{sourceType})
              AND CAST(source_id AS BINARY)=CAST(#{sourceId} AS BINARY)
              AND OCTET_LENGTH(source_id)=OCTET_LENGTH(#{sourceId})
              AND CAST(producer_agent_id AS BINARY)=CAST(#{producerAgentId} AS BINARY)
              AND OCTET_LENGTH(producer_agent_id)=OCTET_LENGTH(#{producerAgentId})
              AND CAST(origin_type AS BINARY)=CAST(#{originType} AS BINARY)
              AND OCTET_LENGTH(origin_type)=OCTET_LENGTH(#{originType})
              AND CAST(origin_id AS BINARY)=CAST(#{originId} AS BINARY)
              AND OCTET_LENGTH(origin_id)=OCTET_LENGTH(#{originId})
            ORDER BY created_at DESC,run_id ASC LIMIT 1
            FOR UPDATE
            """)
    OutputRunBindingEntity findExactByOrigin(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId,
            @Param("producerAgentId") String producerAgentId,
            @Param("originType") String originType,
            @Param("originId") String originId);

    @Update("""
            UPDATE output_run_binding
            SET state='RESULT_SUBMITTED',updated_at=#{updatedAt},row_version=row_version+1
            WHERE tenant_id=#{expected.tenantId} AND client_id=#{expected.clientId}
              AND run_id=#{expected.runId} AND source_type=#{expected.sourceType}
              AND source_id=#{expected.sourceId}
              AND producer_agent_id=#{expected.producerAgentId}
              AND binding_id=#{expected.bindingId}
              AND work_item_id=#{expected.workItemId}
              AND state='ACTIVE' AND policy_version=1
              AND row_version=#{expected.rowVersion}
              AND CAST(tenant_id AS BINARY)=CAST(#{expected.tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{expected.tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{expected.clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{expected.clientId})
              AND CAST(run_id AS BINARY)=CAST(#{expected.runId} AS BINARY)
              AND OCTET_LENGTH(run_id)=OCTET_LENGTH(#{expected.runId})
              AND CAST(source_type AS BINARY)=CAST(#{expected.sourceType} AS BINARY)
              AND OCTET_LENGTH(source_type)=OCTET_LENGTH(#{expected.sourceType})
              AND CAST(source_id AS BINARY)=CAST(#{expected.sourceId} AS BINARY)
              AND OCTET_LENGTH(source_id)=OCTET_LENGTH(#{expected.sourceId})
              AND CAST(producer_agent_id AS BINARY)=CAST(#{expected.producerAgentId} AS BINARY)
              AND OCTET_LENGTH(producer_agent_id)=OCTET_LENGTH(#{expected.producerAgentId})
              AND CAST(binding_id AS BINARY)=CAST(#{expected.bindingId} AS BINARY)
              AND OCTET_LENGTH(binding_id)=OCTET_LENGTH(#{expected.bindingId})
              AND CAST(work_item_id AS BINARY)=CAST(#{expected.workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id)=OCTET_LENGTH(#{expected.workItemId})
            """)
    int markResultSubmitted(
            @Param("expected") OutputRunBindingEntity expected,
            @Param("updatedAt") long updatedAt);

}
