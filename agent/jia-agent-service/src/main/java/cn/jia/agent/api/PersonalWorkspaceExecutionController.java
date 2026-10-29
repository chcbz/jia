package cn.jia.agent.api;

import cn.jia.core.entity.JsonRequest;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Browser JWT entry for private owner-only execution requests. It does not create a public bounty. */
@RestController
@RequestMapping("/agent/personal-workspace")
public class PersonalWorkspaceExecutionController {
    private static final String CACHE_CONTROL = "private, no-store";
    private final PersonalWorkspaceExecutionService service;
    public PersonalWorkspaceExecutionController(PersonalWorkspaceExecutionService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(value = "/executions/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<PersonalWorkspaceExecutionService.ExecutionCapabilities>> capabilities(Authentication authentication) {
        scope(authentication);
        return cn.jia.core.config.ApiJsonResponses.success(ok(service.capabilities()));
    }

    @PostMapping(value = "/executions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<WorkspaceExecutionWire.Execution>> create(
            @RequestBody JsonRequest<CreateRequest> protocolRequest, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication authentication) {
        CreateRequest request = protocolRequest == null ? null : protocolRequest.data();
        if (request == null || request.inputs() == null || request.inputs().stream().anyMatch(Objects::isNull)) throw new RequestFailure();
        PersonalWorkspaceExecutionService.ExecutionView view = service.create(scope(authentication),
                new PersonalWorkspaceExecutionService.CreateCommand(request.conversationId(), request.targetAgentId(),
                        request.taskId(), request.instruction(), request.outputContentMimeType(), request.inputs().stream()
                        .map(input -> new PersonalWorkspaceExecutionService.InputSelection(input.fileId(), version(input.version())))
                        .toList()), key);
        return cn.jia.core.config.ApiJsonResponses.success(ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(view), WorkspaceExecutionWire::from);
    }

    @GetMapping(value = "/executions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<PersonalWorkspaceExecutionService.ExecutionHistoryView>> list(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String beforeCreatedAt,
            @RequestParam(required = false) String beforeExecutionId,
            Authentication authentication) {
        if ((beforeCreatedAt == null) != (beforeExecutionId == null)) throw new RequestFailure();
        Long cursorCreatedAt = beforeCreatedAt == null ? null : epochMillis(beforeCreatedAt);
        if (beforeExecutionId != null && !valid(beforeExecutionId, 100)) throw new RequestFailure();
        return cn.jia.core.config.ApiJsonResponses.success(ok(service.list(scope(authentication), historyLimit(limit),
                cursorCreatedAt, beforeExecutionId)));
    }

    @GetMapping(value = "/executions/request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<WorkspaceExecutionWire.Execution>> request(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication authentication) {
        if (!valid(key, 100)) throw new RequestFailure();
        return cn.jia.core.config.ApiJsonResponses.success(ok(service.getByIdempotencyKey(scope(authentication), key)), WorkspaceExecutionWire::from);
    }

    @GetMapping(value = "/executions/{executionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<WorkspaceExecutionWire.Execution>> get(@PathVariable String executionId,
            Authentication authentication) {
        return cn.jia.core.config.ApiJsonResponses.success(ok(service.get(scope(authentication), executionId)), WorkspaceExecutionWire::from);
    }

    @PostMapping(value = "/executions/{executionId}/revoke-inputs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<WorkspaceExecutionWire.Execution>> revoke(@PathVariable String executionId,
            @RequestBody JsonRequest<RevokeRequest> protocolRequest, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication authentication) {
        RevokeRequest request = protocolRequest == null ? null : protocolRequest.data();
        if (request == null) throw new RequestFailure();
        return cn.jia.core.config.ApiJsonResponses.success(ok(service.revokeInputs(scope(authentication), executionId, decimal(request.expectedGrantRevision()), key)), WorkspaceExecutionWire::from);
    }

    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> failure(PersonalWorkspaceExecutionService.Failure failure) {
        return switch (failure.getReason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid execution request");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Execution is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Operation key conflicts with a different request");
            case GRANT_CHANGED -> error(HttpStatus.CONFLICT, "GRANT_CHANGED", "Execution input grant changed");
            case GRANT_REVOKED -> error(HttpStatus.CONFLICT, "GRANT_REVOKED", "Execution input grant was revoked");
            case CAPABILITY_UNAVAILABLE -> error(HttpStatus.UNPROCESSABLE_ENTITY, "CAPABILITY_UNAVAILABLE", "Execution capability is not available");
            case TASK_CONFLICT -> error(HttpStatus.CONFLICT, "TASK_CONFLICT", "Task execution state changed; refresh and retry");
            case OUTPUT_CONFLICT, OUTPUT_MISSING, STORAGE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_UNAVAILABLE", "Execution is temporarily unavailable");
        };
    }
    @ExceptionHandler({IllegalArgumentException.class, RequestFailure.class})
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> malformed(Exception ignored) { return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid execution request"); }
    @ExceptionHandler(ScopeFailure.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> scopeFailure(ScopeFailure ignored) {
        return error(HttpStatus.FORBIDDEN, "EXECUTION_FORBIDDEN", "Execution scope is unavailable");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<Void>> unexpected(Exception ignored, HttpServletRequest request) { return error(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_UNAVAILABLE", "Execution is temporarily unavailable"); }

    private static PersonalWorkspaceExecutionService.OwnerScope scope(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) throw new RequestFailure();
        Map<String,Object> claims = jwt.getToken().getClaims(); Object owner=claims.get("jiacn"), client=claims.get("client_id");
        if (!(owner instanceof String o) || !(client instanceof String c) || !valid(o,50) || !valid(c,50) || "0".equals(o)) throw new RequestFailure();
        final String tenant;
        try { tenant = cn.jia.core.security.TenantClaimPolicy.resolve(claims, "0"); }
        catch (IllegalArgumentException invalid) { throw new ScopeFailure(); }
        return new PersonalWorkspaceExecutionService.OwnerScope(tenant, c, o);
    }
    private static int historyLimit(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]{0,2}")) throw new RequestFailure();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 100) throw new RequestFailure();
            return parsed;
        } catch (NumberFormatException malformed) {
            throw new RequestFailure();
        }
    }
    private static long epochMillis(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,18})")) throw new RequestFailure();
        try { return Long.parseLong(value); }
        catch (NumberFormatException malformed) { throw new RequestFailure(); }
    }
    private static int version(String value) { if (value==null || !value.matches("[1-9][0-9]{0,9}")) throw new RequestFailure(); try { return Math.toIntExact(Long.parseLong(value)); } catch (ArithmeticException failure) { throw new RequestFailure(); } }
    private static long decimal(String value) { if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new RequestFailure(); try{return Long.parseLong(value);}catch(NumberFormatException failure){throw new RequestFailure();} }
    private static boolean valid(String value,int max) { return value!=null&&!value.isBlank()&&value.equals(value.strip())&&value.codePointCount(0,value.length())<=max&&!value.chars().anyMatch(Character::isISOControl); }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(MediaType.APPLICATION_JSON).body(body); }
    private static ResponseEntity<cn.jia.core.entity.JsonResult<Void>> error(HttpStatus status,String code,String message) { return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(cn.jia.core.entity.JsonResult.<Void>failure(code,message)); }
    public record CreateRequest(String conversationId, String targetAgentId, String taskId, String instruction,
                                String outputContentMimeType, List<InputRequest> inputs) { }
    public record InputRequest(String fileId, String version) { }
    public record RevokeRequest(String expectedGrantRevision) { }
    public record ErrorBody(String code,String message) { }
    private static final class ScopeFailure extends RuntimeException { }
    private static final class RequestFailure extends RuntimeException { }
}
