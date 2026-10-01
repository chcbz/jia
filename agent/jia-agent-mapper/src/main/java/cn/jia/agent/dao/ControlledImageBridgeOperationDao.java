package cn.jia.agent.dao;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
public interface ControlledImageBridgeOperationDao {
    ControlledImageBridgeOperationEntity find(String tenantId,String clientId,String ownerJiacn,String taskId,String key);
    ControlledImageBridgeOperationEntity lock(String tenantId,String clientId,String ownerJiacn,String taskId,String key);
    void insert(ControlledImageBridgeOperationEntity row);
}
