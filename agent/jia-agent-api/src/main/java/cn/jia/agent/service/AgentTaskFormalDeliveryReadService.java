package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;

import java.util.List;

/** Owner-scoped, read-only R2 formal-delivery batch projection. */
public interface AgentTaskFormalDeliveryReadService {
    record HistoryPage(List<AgentTaskFormalDeliveryViewDTO> items, String nextCursor, boolean hasMore) { }
    AgentTaskFormalDeliveryViewDTO currentForTaskOwner(String tenantId, String clientId, String ownerJiacn, String taskId, boolean includeAccepted);
    HistoryPage historyForTaskOwner(String tenantId, String clientId, String ownerJiacn, String taskId, Long beforeRevision);
    List<AgentTaskFormalDeliveryViewDTO> listForTaskOwner(
            String tenantId, String clientId, String ownerJiacn, String taskId);
}
