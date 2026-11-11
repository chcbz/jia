package cn.jia.agent.api;

import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.usage.PlatformKnowledgeUsageRecorder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.economy.usage.UsageCostConflict;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/internal/agent/hosting-leases")
public final class PlatformKnowledgeUsageController {
    public record Request(String handoverId, PlatformKnowledgeUsageRecorder.Evidence observation) { }
    public record Receipt(String handoverId, String attemptId, String revision, String status, String costYuan) { }
    private final PlatformKnowledgeUsageRecorder recorder;
    private final AgentRuntimeV1Service authentication;
    public PlatformKnowledgeUsageController(PlatformKnowledgeUsageRecorder recorder, AgentRuntimeV1Service authentication) {
        this.recorder = recorder; this.authentication = authentication;
    }
    @PostMapping(value="/{leaseId}/knowledge-usage", consumes="application/json", produces="application/json")
    public ResponseEntity<JsonResult<Receipt>> record(@PathVariable String leaseId, @RequestBody JsonRequest<Request> request,
                                                      HttpServletRequest servletRequest) {
        var body = request == null ? null : request.data();
        if (!id(leaseId) || body == null || !id(body.handoverId()) || body.observation() == null) throw new IllegalArgumentException();
        var item = body.observation();
        if (!id(item.batchId()) || item.stage() == null || !Set.of("extract", "privacy-review").contains(item.stage())
                || item.phase() == null || !text(item.channel()) || !text(item.requestedModel()) || !text(item.requestedTier())
                || item.phase() == PlatformKnowledgeUsageRecorder.Phase.STARTED && item.evidence() != null && !item.evidence().isNull()
                || item.evidence() != null && !item.evidence().isNull() && !item.evidence().isObject()) throw new IllegalArgumentException();
        String authorization = maintenanceBearer(servletRequest);
        var receipt = authentication.withInstallationAuthorization(authorization,
                installation -> recorder.record(installation, leaseId, body.handoverId(), item));
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(JsonResult.success(new Receipt(body.handoverId(),
                receipt.attemptId(), Long.toString(receipt.revision()), receipt.status().name(),
                receipt.costYuan() == null ? null : receipt.costYuan().toPlainString())));
    }
    private static String maintenanceBearer(HttpServletRequest request) {
        var headers = java.util.Collections.list(request.getHeaders("Authorization"));
        if (request.getHeader("Origin") != null || request.getHeader("X-API-Key") != null
                || request.getQueryString() != null || headers.size() != 1
                || !headers.getFirst().matches("Bearer rta1_[0-9a-f]{64}"))
            throw new cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException("AGENT_FORBIDDEN", "Maintenance authorization rejected");
        return headers.getFirst().substring(7);
    }
    @ExceptionHandler(cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException.class)
    public ResponseEntity<JsonResult<Void>> unauthorized() { return error(403, "KNOWLEDGE_MAINTENANCE_FORBIDDEN"); }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<JsonResult<Void>> unavailableStorage() { return error(503, "KNOWLEDGE_MAINTENANCE_UNAVAILABLE"); }
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
