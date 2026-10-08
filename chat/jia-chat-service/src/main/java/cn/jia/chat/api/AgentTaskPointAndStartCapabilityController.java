package cn.jia.chat.api;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import cn.jia.agent.service.NativeBountyExecutionSessionLookup;
import cn.jia.chat.service.PointAndStartCapabilityService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Owner JWT-only observation. It grants no authority and performs no assignment or execution. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
@ConditionalOnProperty(prefix = "chat.native-bounty-capability", name = "enabled",
        havingValue = "true")
public final class AgentTaskPointAndStartCapabilityController {
    static final String CACHE_CONTROL = "private, no-store";
    private final PointAndStartCapabilityService capabilities;

    public AgentTaskPointAndStartCapabilityController(PointAndStartCapabilityService capabilities) {
        this.capabilities = Objects.requireNonNull(capabilities);
    }

    @GetMapping(value = "/{taskId}/point-and-start-capability",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<PointAndStartCapabilityService.Capability>> read(
            @PathVariable String taskId,
            @RequestParam(name = "targetAgentId", required = false) String targetAgentId,
            HttpServletRequest request, Authentication authentication) {
        AgentTaskExecutionGrantService.Scope scope = requireOwnerJwt(authentication);
        exactId(taskId, 100); exactId(targetAgentId, 100);
        Map<String, String[]> parameters = request.getParameterMap();
        String[] targets = parameters.get("targetAgentId");
        if (parameters.size() != 1 || targets == null || targets.length != 1
                || !Objects.equals(targetAgentId, targets[0])) throw new InvalidRequest();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff")
                .body(JsonResult.success(capabilities.read(scope, taskId, targetAgentId)));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<JsonResult<Void>> authentication() {
        return error(HttpStatus.UNAUTHORIZED, "POINT_AND_START_UNAUTHENTICATED",
                "Authentication is required");
    }

    @ExceptionHandler({InvalidRequest.class, IllegalArgumentException.class})
    public ResponseEntity<JsonResult<Void>> invalid() {
        return error(HttpStatus.BAD_REQUEST, "POINT_AND_START_BAD_REQUEST",
                "Invalid capability request");
    }

    @ExceptionHandler(AgentTaskPointAndStartPolicyService.Failure.class)
    public ResponseEntity<JsonResult<Void>> policyFailure(
            AgentTaskPointAndStartPolicyService.Failure failure) {
        return switch (failure.reason()) {
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "POINT_AND_START_NOT_FOUND",
                    "Task or target Agent is unavailable in the requested scope");
            case SOURCE_UNAVAILABLE, INTEGRITY_ERROR -> unavailable(failure);
        };
    }

    @ExceptionHandler({NativeBountyExecutionSessionLookup.SourceUnavailable.class,
            PointAndStartCapabilityService.SourceUnavailable.class})
    public ResponseEntity<JsonResult<Void>> sourceUnavailable(RuntimeException failure) {
        return unavailable(failure);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> unexpected(Exception failure) {
        return unavailable(failure);
    }

    private static AgentTaskExecutionGrantService.Scope requireOwnerJwt(
            Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !authentication.isAuthenticated()) throw new AuthenticationFailure();
        Object owner = jwt.getToken().getClaims().get("jiacn");
        Object client = jwt.getToken().getClaims().get("client_id");
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !exactIdentity(ownerJiacn, 50) || "0".equals(ownerJiacn)
                || !exactIdentity(clientId, 50)) throw new AuthenticationFailure();
        return new AgentTaskExecutionGrantService.Scope("0", clientId, ownerJiacn);
    }

    private static void exactId(String value, int max) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (max - 1) + "}"))
            throw new InvalidRequest();
    }

    private static boolean exactIdentity(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= max && value.chars().noneMatch(Character::isISOControl);
    }

    private static ResponseEntity<JsonResult<Void>> unavailable(Throwable failure) {
        log.error("Point-and-start capability source unavailable: type={}",
                failure.getClass().getName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "POINT_AND_START_SOURCE_UNAVAILABLE",
                "Capability source is temporarily unavailable");
    }

    private static ResponseEntity<JsonResult<Void>> error(
            HttpStatus status, String code, String message) {
        JsonResult<Void> body = JsonResult.failure(code, message);
        body.setStatus(status.value());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(body);
    }

    private static final class AuthenticationFailure extends RuntimeException { }
    private static final class InvalidRequest extends RuntimeException { }
}
