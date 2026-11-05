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

    @Select("""
            SELECT d.* FROM agent_task_formal_delivery d
             WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId} AND d.task_id=#{taskId}
               AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(d.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(d.task_id)=OCTET_LENGTH(#{taskId})
             ORDER BY d.revision DESC, d.id DESC
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskFormalDeliveryEntity selectLatestTaskForUpdate(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO agent_task_formal_delivery
              (task_id,work_item_id,delivery_id,revision,supersedes_delivery_id,
               producer_agent_id,run_id,summary,state,submission_digest,manifest_artifact_id,manifest_artifact_version,
               submitted_at,reviewed_by_jiacn,review_reason,reviewed_at,version,
               tenant_id,client_id,create_time,update_time)
            VALUES
              (#{taskId},#{workItemId},#{deliveryId},#{revision},#{supersedesDeliveryId},
               #{producerAgentId},#{runId},#{summary},#{state},#{submissionDigest},#{manifestArtifactId},#{manifestArtifactVersion},
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
            SELECT d.* FROM agent_task_formal_delivery d
             WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId} AND d.task_id=#{taskId}
               AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(d.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(d.task_id)=OCTET_LENGTH(#{taskId})
             ORDER BY d.revision DESC, d.id DESC
             LIMIT #{limit}
            """)
    List<AgentTaskFormalDeliveryEntity> selectTaskDeliveries(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT d.* FROM agent_task_formal_delivery d
             WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId} AND d.task_id=#{taskId}
               AND CAST(d.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(d.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(d.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(d.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(d.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(d.task_id)=OCTET_LENGTH(#{taskId})
               <if test="state != null">AND d.state=#{state}</if>
               <if test="beforeRevision != null">AND d.revision &lt; #{beforeRevision}</if>
             ORDER BY d.revision DESC, d.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<AgentTaskFormalDeliveryEntity> selectTaskDeliveryWindow(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("taskId") String taskId, @Param("state") String state,
            @Param("beforeRevision") Long beforeRevision, @Param("limit") int limit);

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

    @Select("""
            <script>
            SELECT i.delivery_id,i.item_order,i.artifact_id,i.artifact_version,i.content_hash,i.purpose,
                   w.version AS work_item_version,a.title,OCTET_LENGTH(a.content) AS inline_byte_length,
                   CASE WHEN JSON_VALID(a.metadata_json)
                     THEN JSON_EXTRACT(a.metadata_json,'$._cyfArtifactStorageV1') END AS storage_metadata
              FROM agent_task_formal_delivery d
              JOIN agent_task_formal_delivery_item i ON i.tenant_id=d.tenant_id AND i.client_id=d.client_id
               AND BINARY i.tenant_id=BINARY d.tenant_id AND BINARY i.client_id=BINARY d.client_id
               AND i.delivery_id=d.delivery_id AND BINARY i.delivery_id=BINARY d.delivery_id
              LEFT JOIN agent_task_work_item w ON w.tenant_id=d.tenant_id AND w.client_id=d.client_id
               AND BINARY w.tenant_id=BINARY d.tenant_id AND BINARY w.client_id=BINARY d.client_id
               AND w.task_id=d.task_id AND w.work_item_id=d.work_item_id
               AND BINARY w.task_id=BINARY d.task_id AND BINARY w.work_item_id=BINARY d.work_item_id
               AND w.owner_jiacn=#{ownerJiacn} AND BINARY w.owner_jiacn=BINARY #{ownerJiacn}
              LEFT JOIN agent_task_artifact a ON a.tenant_id=d.tenant_id AND a.client_id=d.client_id
               AND BINARY a.tenant_id=BINARY d.tenant_id AND BINARY a.client_id=BINARY d.client_id
               AND a.task_id=d.task_id AND a.artifact_id=i.artifact_id AND a.artifact_version=i.artifact_version
               AND BINARY a.task_id=BINARY d.task_id AND BINARY a.artifact_id=BINARY i.artifact_id
               AND a.owner_jiacn=#{ownerJiacn} AND BINARY a.owner_jiacn=BINARY #{ownerJiacn} AND BINARY a.content_hash=BINARY i.content_hash
             WHERE d.tenant_id=#{tenantId} AND d.client_id=#{clientId} AND d.task_id=#{taskId}
               AND BINARY d.tenant_id=BINARY #{tenantId} AND BINARY d.client_id=BINARY #{clientId}
               AND BINARY d.task_id=BINARY #{taskId}
               AND d.delivery_id IN
               <foreach collection="deliveryIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND BINARY d.delivery_id IN
               <foreach collection="deliveryIds" item="id" open="(" separator="," close=")">BINARY #{id}</foreach>
             ORDER BY d.revision DESC,i.item_order ASC,i.id ASC
            </script>
            """)
    List<cn.jia.agent.dao.AgentTaskFormalDeliveryDao.DisplayItem> selectDisplayItems(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("deliveryIds") List<String> deliveryIds);

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
