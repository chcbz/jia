package cn.jia.agent.api;

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

    @PostMapping(value = "/executions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> create(
            @RequestBody CreateRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication authentication) {
        if (request == null || request.inputs() == null) throw new RequestFailure();
        PersonalWorkspaceExecutionService.ExecutionView view = service.create(scope(authentication),
                new PersonalWorkspaceExecutionService.CreateCommand(request.conversationId(), request.targetAgentId(),
                        request.taskId(), request.instruction(), request.inputs().stream()
                        .map(input -> new PersonalWorkspaceExecutionService.InputSelection(input.fileId(), version(input.version())))
                        .toList()), key);
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(view);
    }

    @GetMapping(value = "/executions/{executionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> get(@PathVariable String executionId,
            Authentication authentication) {
        return ok(service.get(scope(authentication), executionId));
    }

    @PostMapping(value = "/executions/{executionId}/revoke-inputs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> revoke(@PathVariable String executionId,
            @RequestBody RevokeRequest request, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            Authentication authentication) {
        if (request == null) throw new RequestFailure();
        return ok(service.revokeInputs(scope(authentication), executionId, decimal(request.expectedGrantRevision()), key));
    }

    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<ErrorBody> failure(PersonalWorkspaceExecutionService.Failure failure) {
        return switch (failure.getReason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid execution request");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Execution is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Operation key conflicts with a different request");
            case GRANT_CHANGED -> error(HttpStatus.CONFLICT, "GRANT_CHANGED", "Execution input grant changed");
            case GRANT_REVOKED -> error(HttpStatus.CONFLICT, "GRANT_REVOKED", "Execution input grant was revoked");
            case CAPABILITY_UNAVAILABLE -> error(HttpStatus.UNPROCESSABLE_ENTITY, "CAPABILITY_UNAVAILABLE", "Execution capability is not available");
            case OUTPUT_CONFLICT, OUTPUT_MISSING, STORAGE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_UNAVAILABLE", "Execution is temporarily unavailable");
        };
    }
    @ExceptionHandler({IllegalArgumentException.class, RequestFailure.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) { return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid execution request"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) { return error(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_UNAVAILABLE", "Execution is temporarily unavailable"); }

    private static PersonalWorkspaceExecutionService.OwnerScope scope(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) throw new RequestFailure();
        Map<String,Object> claims = jwt.getToken().getClaims(); Object owner=claims.get("jiacn"), client=claims.get("client_id");
        if (!(owner instanceof String o) || !(client instanceof String c) || !valid(o,50) || !valid(c,50) || "0".equals(o)) throw new RequestFailure();
        return new PersonalWorkspaceExecutionService.OwnerScope("0", c, o);
    }
    private static int version(String value) { if (value==null || !value.matches("[1-9][0-9]{0,9}")) throw new RequestFailure(); try { return Math.toIntExact(Long.parseLong(value)); } catch (ArithmeticException failure) { throw new RequestFailure(); } }
    private static long decimal(String value) { if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new RequestFailure(); try{return Long.parseLong(value);}catch(NumberFormatException failure){throw new RequestFailure();} }
    private static boolean valid(String value,int max) { return value!=null&&!value.isBlank()&&value.equals(value.strip())&&value.codePointCount(0,value.length())<=max&&!value.chars().anyMatch(Character::isISOControl); }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(MediaType.APPLICATION_JSON).body(body); }
    private static ResponseEntity<ErrorBody> error(HttpStatus status,String code,String message) { return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL).contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(new ErrorBody(code,message)); }
    public record CreateRequest(String conversationId, String targetAgentId, String taskId, String instruction, List<InputRequest> inputs) { }
    public record InputRequest(String fileId, String version) { }
    public record RevokeRequest(String expectedGrantRevision) { }
    public record ErrorBody(String code,String message) { }
    private static final class RequestFailure extends RuntimeException { }
}
