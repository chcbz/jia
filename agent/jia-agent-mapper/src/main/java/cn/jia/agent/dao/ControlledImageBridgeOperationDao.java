package cn.jia.agent.dao;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
public interface ControlledImageBridgeOperationDao {
    ControlledImageBridgeOperationEntity find(String tenantId,String clientId,String ownerJiacn,String taskId,String key);
    ControlledImageBridgeOperationEntity lock(String tenantId,String clientId,String ownerJiacn,String taskId,String key);
    ControlledImageBridgeOperationEntity lockByAuthority(String tenantId,String clientId,String ownerJiacn,
            String taskId,String consentId,String grantId);
    ControlledImageBridgeOperationEntity findByOperationGrant(String tenantId,String clientId,String ownerJiacn,
            String taskId,String operationGrantId);
    ControlledImageBridgeOperationEntity lockByOperationGrant(String tenantId,String clientId,String ownerJiacn,
            String taskId,String operationGrantId);
    void insert(ControlledImageBridgeOperationEntity row);
}
