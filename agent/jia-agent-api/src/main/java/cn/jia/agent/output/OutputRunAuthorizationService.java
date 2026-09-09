package cn.jia.agent.output;

import cn.jia.agent.output.dto.OutputAuthReceiptDTO;
import cn.jia.agent.output.dto.OutputContextDTO;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Trusted run creation and opaque-ticket authorization boundary. */
public interface OutputRunAuthorizationService {
    Optional<OutputContextDTO> createOrRecoverRun(OutputRunRequest request);

    /**
     * Establishes all requested run locks before acquiring any identity/runtime lock. The returned
     * map contains only producers whose current runtime negotiated output delivery; policy-0
     * producers without a fresh capability snapshot remain on the legacy command wire.
     */
    Map<String, OutputContextDTO> createOrRecoverRuns(
            List<OutputRunRequest> requests, List<String> identityAgentIds);

    /** Returns the exact currently registered runtime generation allowed to receive this run. */
    String requireFreshDispatchRuntime(
            String tenantId, String clientId, String producerAgentId, String runId);

    OutputAuthReceiptDTO issueTicket(
            String tenantId, String clientId, String producerAgentId,
            String runtimeInstanceId, String messageId, String runId);

    OutputTicketAuthorization authorizeTicket(
            String rawBearer, String requiredOperation, boolean receiptReplay);
}
