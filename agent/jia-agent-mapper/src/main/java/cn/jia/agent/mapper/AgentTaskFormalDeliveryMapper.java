package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Scoped persistence only; service-layer transaction and ACL decisions remain R2 follow-up work. */
public interface AgentTaskFormalDeliveryMapper extends BaseMapper<AgentTaskFormalDeliveryEntity> {
    @Select("""
            SELECT d.* FROM agent_task_formal_delivery d
             WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId}
               AND d.delivery_id=#{deliveryId}
               AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(d.delivery_id AS BINARY)=CAST(#{deliveryId} AS BINARY)
               AND OCTET_LENGTH(d.delivery_id)=OCTET_LENGTH(#{deliveryId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskFormalDeliveryEntity selectExactForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("deliveryId") String deliveryId);

    @Select("""
            SELECT d.* FROM agent_task_formal_delivery d
             WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId} AND d.task_id=#{taskId}
               AND d.revision=#{revision}
               AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(d.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(d.task_id)=OCTET_LENGTH(#{taskId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskFormalDeliveryEntity selectTaskRevisionForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("revision") long revision);

    @Insert("""
            INSERT INTO agent_task_formal_delivery
              (task_id,work_item_id,delivery_id,revision,supersedes_delivery_id,
               producer_agent_id,run_id,summary,state,manifest_artifact_id,manifest_artifact_version,
               submitted_at,reviewed_by_jiacn,review_reason,reviewed_at,version,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{taskId},#{workItemId},#{deliveryId},#{revision},#{supersedesDeliveryId},
               #{producerAgentId},#{runId},#{summary},#{state},#{manifestArtifactId},#{manifestArtifactVersion},
               #{submittedAt},#{reviewedByJiacn},#{reviewReason},#{reviewedAt},#{version},
               #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertFormalDelivery(AgentTaskFormalDeliveryEntity entity);

    @Insert("""
            INSERT INTO agent_task_formal_delivery_item
              (delivery_id,artifact_id,artifact_version,content_hash,purpose,item_order,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{deliveryId},#{artifactId},#{artifactVersion},#{contentHash},#{purpose},#{itemOrder},
               #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertDeliveryItem(AgentTaskFormalDeliveryItemEntity entity);

    @Select("""
            SELECT i.* FROM agent_task_formal_delivery_item i
             WHERE i.tenant_id=#{tenantId} AND i.client_id=#{clientId} AND i.delivery_id=#{deliveryId}
               AND CAST(i.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(i.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(i.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(i.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(i.delivery_id AS BINARY)=CAST(#{deliveryId} AS BINARY)
               AND OCTET_LENGTH(i.delivery_id)=OCTET_LENGTH(#{deliveryId})
             ORDER BY i.item_order ASC, i.id ASC
            """)
    List<AgentTaskFormalDeliveryItemEntity> selectItems(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("deliveryId") String deliveryId);

    @Update("""
            UPDATE agent_task_formal_delivery
               SET state=#{state}, reviewed_by_jiacn=#{reviewedByJiacn}, review_reason=#{reviewReason},
                   reviewed_at=#{reviewedAt}, version=#{resultVersion}, update_time=#{reviewedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND delivery_id=#{deliveryId}
               AND state=#{expectedState} AND version=#{expectedVersion}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(delivery_id AS BINARY)=CAST(#{deliveryId} AS BINARY)
               AND OCTET_LENGTH(delivery_id)=OCTET_LENGTH(#{deliveryId})
               AND CAST(state AS BINARY)=CAST(#{expectedState} AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH(#{expectedState})
            """)
    int reviewByVersion(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("deliveryId") String deliveryId, @Param("expectedState") String expectedState,
            @Param("expectedVersion") long expectedVersion, @Param("state") String state,
            @Param("reviewedByJiacn") String reviewedByJiacn, @Param("reviewReason") String reviewReason,
            @Param("reviewedAt") long reviewedAt, @Param("resultVersion") long resultVersion);
}
