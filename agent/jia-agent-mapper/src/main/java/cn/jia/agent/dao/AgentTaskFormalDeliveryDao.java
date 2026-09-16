package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;

import java.util.List;

/** R2 persistence boundary. No caller-controlled scope or unversioned review update is allowed. */
public interface AgentTaskFormalDeliveryDao {
    AgentTaskFormalDeliveryEntity findForUpdate(String tenantId, String clientId, String deliveryId);
    AgentTaskFormalDeliveryEntity findTaskRevisionForUpdate(
            String tenantId, String clientId, String taskId, long revision);
    int insert(String tenantId, String clientId, AgentTaskFormalDeliveryEntity delivery);
    int insertItem(String tenantId, String clientId, AgentTaskFormalDeliveryItemEntity item);
    List<AgentTaskFormalDeliveryItemEntity> listItems(String tenantId, String clientId, String deliveryId);
    int reviewByVersion(String tenantId, String clientId, String deliveryId, String expectedState,
            long expectedVersion, String nextState, String reviewedByJiacn, String reviewReason,
            long reviewedAt);
}
