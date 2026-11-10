package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.usage.PlatformKnowledgeUsageRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.usage.UsageCostConflict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/internal/agent/hosting-leases")
public final class PlatformKnowledgeUsageController {
    public record Request(String handoverId, PlatformKnowledgeUsageRecorder.Evidence observation) { }
    public record Receipt(String handoverId, String attemptId, String revision, String status, String costYuan) { }
    private final PlatformKnowledgeUsageRecorder recorder;
    private final AgentRuntimeAuthenticationService authentication;
    public PlatformKnowledgeUsageController(PlatformKnowledgeUsageRecorder recorder, AgentRuntimeAuthenticationService authentication) {
        this.recorder = recorder; this.authentication = authentication;
    }
    @PostMapping(value="/{leaseId}/knowledge-usage", consumes="application/json", produces="application/json")
    public ResponseEntity<JsonResult<Receipt>> record(@PathVariable String leaseId, @RequestBody JsonRequest<Request> request,
                                                      Authentication principal) {
        var body = request == null ? null : request.data();
        if (!id(leaseId) || body == null || !id(body.handoverId()) || body.observation() == null) throw new IllegalArgumentException();
        var item = body.observation();
        if (!id(item.batchId()) || item.stage() == null || !Set.of("extract", "privacy-review").contains(item.stage())
                || item.phase() == null || !text(item.channel()) || !text(item.requestedModel()) || !text(item.requestedTier())
                || item.phase() == PlatformKnowledgeUsageRecorder.Phase.STARTED && item.evidence() != null && !item.evidence().isNull()
                || item.evidence() != null && !item.evidence().isNull() && !item.evidence().isObject()) throw new IllegalArgumentException();
        var receipt = authentication.withNativeFence(principal, () -> {
            var trusted = AgentRuntimeAuthenticationService.requireProof(principal).scope();
            return recorder.record(new AuthenticatedRuntimeOperation.Scope(trusted.tenantId(), trusted.clientId(),
                    trusted.ownerJiacn(), trusted.agentId()), leaseId, body.handoverId(), item);
        });
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(JsonResult.success(new Receipt(body.handoverId(),
                receipt.attemptId(), Long.toString(receipt.revision()), receipt.status().name(),
                receipt.costYuan() == null ? null : receipt.costYuan().toPlainString())));
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"); }
    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<JsonResult<Void>> unavailable() { return error(404, "KNOWLEDGE_RELATIONSHIP_NOT_FOUND"); }
    @ExceptionHandler(UsageCostConflict.class)
    public ResponseEntity<JsonResult<Void>> conflict() { return error(409, "KNOWLEDGE_USAGE_RECEIPT_CONFLICT"); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonResult<Void>> invalid() { return error(400, "KNOWLEDGE_USAGE_INVALID"); }
    private static ResponseEntity<JsonResult<Void>> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "private, no-store")
                .body(JsonResult.failure(code, "Platform usage could not be recorded"));
    }
}
