package cn.jia.agent.dao;

import cn.jia.agent.entity.HallPrivateMarkEntity;

public interface HallPrivateMarkDao {
    HallPrivateMarkEntity current(String tenantId, String clientId, String ownerJiacn, String sourceType, String sourceId);
    HallPrivateMarkEntity byKey(String tenantId, String clientId, String ownerJiacn, String operationKey);
    int insert(HallPrivateMarkEntity mark);
}
