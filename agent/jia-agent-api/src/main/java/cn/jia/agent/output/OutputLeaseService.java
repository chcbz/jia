package cn.jia.agent.output;

import cn.jia.agent.output.dto.OutputLeaseRequestDTO;

/** Transactional HTTP adapter for policy-1 work-item leases. */
public interface OutputLeaseService {
    OutputLeaseHttpResult mutate(
            OutputTicketAuthorization projectedAuthorization,
            String bearer,
            String idempotencyKey,
            String taskId,
            String workItemId,
            String action,
            OutputLeaseRequestDTO request,
            String requestId);

    OutputLeaseHttpResult recover(
            OutputTicketAuthorization projectedAuthorization,
            String bearer,
            String taskId,
            String workItemId,
            String requestId);
}
