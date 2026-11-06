package cn.jia.chat.api;

import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.usage.ChatUsageCostRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.usage.UsageCostConflict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Independent evidence ACK, never a business final, dispatch retry or wallet mutation. */
@RestController
@RequestMapping("/internal/agent/chat/dispatches")
public final class ChatUsageCostController {
    public record Request(ChatUsageCostRecorder.Phase phase, String requestedModel, JsonNode usageEvidence) { }
    public record Receipt(String dispatchId, String attemptId, String revision, String status, String costYuan) { }
    private final ChatUsageCostRecorder recorder;
    private final AuthenticatedRuntimeOperation authentication;
    public ChatUsageCostController(ChatUsageCostRecorder recorder, AuthenticatedRuntimeOperation authentication) {
        this.recorder = recorder; this.authentication = authentication;
    }

    @PostMapping(value="/{dispatchId}/usage", consumes="application/json", produces="application/json")
    public ResponseEntity<JsonResult<Receipt>> record(@PathVariable String dispatchId,
            @RequestBody JsonRequest<Request> protocolRequest, Authentication principal) {
        var body = protocolRequest == null ? null : protocolRequest.data();
        if (body == null || body.phase() == null
                || body.phase() == ChatUsageCostRecorder.Phase.STARTED && body.usageEvidence() != null && !body.usageEvidence().isNull()
                || body.usageEvidence() != null && !body.usageEvidence().isNull() && !body.usageEvidence().isObject())
            throw new IllegalArgumentException("Invalid CHAT usage evidence");
        var receipt = authentication.withAuthenticatedRuntime(principal, scope -> recorder.record(
                scope, dispatchId,
                new ChatUsageCostRecorder.Evidence(body.phase(), body.requestedModel(),
                        body.usageEvidence() == null ? "null" : body.usageEvidence().toString())));
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(JsonResult.success(
                new Receipt(dispatchId, receipt.attemptId(), Long.toString(receipt.revision()), receipt.status().name(),
                        receipt.costYuan() == null ? null : receipt.costYuan().toPlainString())));
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(ChatDeliberationException ignored) {
        return error(404, "CHAT_USAGE_DISPATCH_NOT_FOUND");
    }
    @ExceptionHandler(UsageCostConflict.class)
    public ResponseEntity<JsonResult<Void>> conflict(UsageCostConflict ignored) {
        return error(409, "CHAT_USAGE_RECEIPT_CONFLICT");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonResult<Void>> invalid(IllegalArgumentException ignored) {
        return error(400, "CHAT_USAGE_INVALID");
    }
    private static ResponseEntity<JsonResult<Void>> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
                .body(JsonResult.failure(code, "CHAT usage evidence could not be recorded"));
    }
}
