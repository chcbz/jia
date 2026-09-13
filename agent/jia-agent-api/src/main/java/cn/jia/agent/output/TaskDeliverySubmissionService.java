package cn.jia.agent.output;

import cn.jia.agent.output.dto.TaskDeliverySubmitDTO;

public interface TaskDeliverySubmissionService {
    TaskDeliveryHttpResult submit(
            OutputTicketAuthorization statusAuthorization,
            String rawBearer, String idempotencyKey, String taskId,
            TaskDeliverySubmitDTO request, String requestId);
}
