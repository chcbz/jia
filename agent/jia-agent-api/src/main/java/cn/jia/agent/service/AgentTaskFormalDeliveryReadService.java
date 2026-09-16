package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;

import java.util.List;

/** Owner-scoped, read-only R2 formal-delivery batch projection. */
public interface AgentTaskFormalDeliveryReadService {
    List<AgentTaskFormalDeliveryViewDTO> listForTaskOwner(
            String tenantId, String clientId, String taskId);
}
