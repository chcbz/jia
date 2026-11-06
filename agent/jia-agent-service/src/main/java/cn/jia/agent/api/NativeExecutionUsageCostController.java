package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.usage.NativeExecutionUsageCostRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.usage.UsageCostConflict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** Cost evidence delivery does not claim, resume or finish an execution. */
@RestController
@RequestMapping("/internal/agent/tasks")
public final class NativeExecutionUsageCostController {
    public record Request(String executionId, String requestedModel, JsonNode usageEvidence) { }
    public record Receipt(String executionId, String attemptId, String revision, String status, String costYuan) { }
    private final NativeExecutionUsageCostRecorder recorder;
    private final AgentRuntimeAuthenticationService authentication;
    public NativeExecutionUsageCostController(NativeExecutionUsageCostRecorder recorder,
            AgentRuntimeAuthenticationService authentication) {
        this.recorder = recorder; this.authentication = authentication;
    }

    @PostMapping(value="/{taskId}/runs/{runId}/conversation/usage", consumes="application/json", produces="application/json")
    public ResponseEntity<JsonResult<Receipt>> record(@PathVariable String taskId, @PathVariable String runId,
            @RequestBody JsonRequest<Request> protocolRequest, Authentication principal) {
        var body = protocolRequest == null ? null : protocolRequest.data();
        if (body == null || body.executionId() == null || !body.executionId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                || body.usageEvidence() != null && !body.usageEvidence().isNull() && !body.usageEvidence().isObject())
            throw new IllegalArgumentException("Invalid native usage evidence");
        var receipt = authentication.withNativeFence(principal, () -> {
            var trusted = AgentRuntimeAuthenticationService.requireProof(principal).scope();
            var scope = new PersonalWorkspaceExecutionService.RuntimeScope(trusted.tenantId(), trusted.clientId(),
                    trusted.ownerJiacn(), trusted.agentId(), trusted.runtimeInstanceId());
            return recorder.record(scope, taskId, runId, new NativeExecutionUsageCostRecorder.Evidence(
                    body.executionId(), body.requestedModel(), body.usageEvidence() == null ? "null" : body.usageEvidence().toString()));
        });
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(JsonResult.success(
                new Receipt(body.executionId(), receipt.attemptId(), Long.toString(receipt.revision()),
                        receipt.status().name(), receipt.costYuan() == null ? null : receipt.costYuan().toPlainString())));
    }

    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<JsonResult<Void>> unavailable(PersonalWorkspaceExecutionService.Failure ignored) {
        return error(404, "NATIVE_USAGE_EXECUTION_NOT_FOUND");
    }
    @ExceptionHandler(UsageCostConflict.class)
    public ResponseEntity<JsonResult<Void>> conflict(UsageCostConflict ignored) {
        return error(409, "NATIVE_USAGE_RECEIPT_CONFLICT");
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonResult<Void>> invalid(IllegalArgumentException ignored) {
        return error(400, "NATIVE_USAGE_INVALID");
    }
    private static ResponseEntity<JsonResult<Void>> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
                .body(JsonResult.failure(code, "Native usage evidence could not be recorded"));
    }
}
