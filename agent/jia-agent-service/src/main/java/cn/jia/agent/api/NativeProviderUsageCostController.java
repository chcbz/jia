package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.usage.NativeExecutionUsageCostRecorder.Phase;
import cn.jia.agent.service.usage.NativeProviderUsageCostRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.usage.UsageCostConflict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/agent/tasks")
public final class NativeProviderUsageCostController {
    public record Request(String executionId, NativeProviderUsageCostRecorder.Evidence observation) { }
    private final NativeProviderUsageCostRecorder recorder;
    private final AgentRuntimeAuthenticationService authentication;
    public NativeProviderUsageCostController(NativeProviderUsageCostRecorder recorder, AgentRuntimeAuthenticationService authentication) {
        this.recorder = recorder; this.authentication = authentication;
    }
    @PostMapping(value="/{taskId}/runs/{runId}/conversation/provider-usage", consumes="application/json", produces="application/json")
    public ResponseEntity<JsonResult<NativeExecutionUsageCostController.Receipt>> record(@PathVariable String taskId, @PathVariable String runId,
            @RequestBody JsonRequest<Request> request, Authentication principal) {
        var body = request == null ? null : request.data();
        if (body == null || !id(body.executionId()) || body.observation() == null) throw new IllegalArgumentException();
        var item = body.observation();
        if (item.schemaVersion() != 1 || !id(item.invocationId()) || item.phase() == null || item.observedAt() < 0
                || item.protocol() == null || item.requestedModel() == null || item.requestedModel().isBlank()
                || item.phase() == Phase.STARTED && item.evidence() != null && !item.evidence().isNull()
                || item.evidence() != null && !item.evidence().isNull() && !item.evidence().isObject()) throw new IllegalArgumentException();
        var receipt = authentication.withNativeFence(principal, () -> {
            var trusted = AgentRuntimeAuthenticationService.requireProof(principal).scope();
            return recorder.record(new PersonalWorkspaceExecutionService.RuntimeScope(trusted.tenantId(), trusted.clientId(),
                    trusted.ownerJiacn(), trusted.agentId(), trusted.runtimeInstanceId()), taskId, runId, body.executionId(), item);
        });
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(JsonResult.success(
                new NativeExecutionUsageCostController.Receipt(body.executionId(), receipt.attemptId(), Long.toString(receipt.revision()),
                        receipt.status().name(), receipt.costYuan() == null ? null : receipt.costYuan().toPlainString())));
    }
    private static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"); }
    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<JsonResult<Void>> unavailable() { return error(404, "NATIVE_USAGE_EXECUTION_NOT_FOUND"); }
    @ExceptionHandler(UsageCostConflict.class)
    public ResponseEntity<JsonResult<Void>> conflict() { return error(409, "NATIVE_USAGE_RECEIPT_CONFLICT"); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonResult<Void>> invalid() { return error(400, "NATIVE_USAGE_INVALID"); }
    private static ResponseEntity<JsonResult<Void>> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store").body(JsonResult.failure(code, "Provider usage could not be recorded"));
    }
}
