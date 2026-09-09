package cn.jia.agent.output;

import cn.jia.agent.output.dto.OutputAuthReceiptDTO;
import cn.jia.agent.output.dto.OutputContextDTO;

import java.util.Optional;

/** Trusted run creation and opaque-ticket authorization boundary. */
public interface OutputRunAuthorizationService {
    Optional<OutputContextDTO> createOrRecoverRun(OutputRunRequest request);

    OutputAuthReceiptDTO issueTicket(
            String tenantId, String clientId, String producerAgentId,
            String runtimeInstanceId, String messageId, String runId);

    OutputTicketAuthorization authorizeTicket(
            String rawBearer, String requiredOperation, boolean receiptReplay);
}
