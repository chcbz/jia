package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;

import java.util.List;

/** R2 persistence boundary. No caller-controlled scope or unversioned review update is allowed. */
public interface AgentTaskFormalDeliveryDao {
    /** Metadata only for the already authorized delivery window; never reads content bytes or storage paths. */
    record DisplayItem(String deliveryId, Integer itemOrder, String artifactId, Integer artifactVersion,
            String contentHash, String purpose, Long workItemVersion, String title,
            Long inlineByteLength, String storageMetadata) { }
    List<DisplayItem> listDisplayItems(String tenantId, String clientId, String ownerJiacn,
            String taskId, List<String> deliveryIds);

    AgentTaskFormalDeliveryEntity findForUpdate(String tenantId, String clientId, String deliveryId);
    AgentTaskFormalDeliveryEntity findTaskRevisionForUpdate(
            String tenantId, String clientId, String taskId, long revision);
    AgentTaskFormalDeliveryEntity findLatestTaskForUpdate(
            String tenantId, String clientId, String taskId);
    int insert(String tenantId, String clientId, AgentTaskFormalDeliveryEntity delivery);
    int insertItem(String tenantId, String clientId, AgentTaskFormalDeliveryItemEntity item);
    List<AgentTaskFormalDeliveryEntity> listTaskDeliveries(
            String tenantId, String clientId, String taskId, int limit);
    List<AgentTaskFormalDeliveryEntity> listTaskDeliveryWindow(
            String tenantId, String clientId, String taskId, String state, Long beforeRevision, int limit);
    List<AgentTaskFormalDeliveryItemEntity> listItems(String tenantId, String clientId, String deliveryId);
    int reviewByVersion(String tenantId, String clientId, String deliveryId, String expectedState,
            long expectedVersion, String nextState, String reviewedByJiacn, String reviewReason,
            long reviewedAt);
}
