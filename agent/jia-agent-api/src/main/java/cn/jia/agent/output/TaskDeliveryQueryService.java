package cn.jia.agent.output;

import cn.jia.agent.output.dto.TaskDeliveryPageDTO;

/** Owner-only read boundary for immutable formal task-delivery batches. */
public interface TaskDeliveryQueryService {
    TaskDeliveryPageDTO list(String tenantId, String clientId, String jiacn,
            String taskId, String cursor, Integer limit);
}
