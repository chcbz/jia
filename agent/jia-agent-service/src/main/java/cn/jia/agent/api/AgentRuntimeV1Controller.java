package cn.jia.agent.api;

import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentRuntimeV1AckRequest;
import cn.jia.agent.entity.AgentRuntimeV1EnrollmentRequest;
import cn.jia.agent.entity.AgentRuntimeV1EnrollmentResult;
import cn.jia.agent.entity.AgentRuntimeV1InstallationRequest;
import cn.jia.agent.entity.AgentRuntimeV1InstallationView;
import cn.jia.agent.entity.AgentRuntimeV1RuntimeRequest;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Versioned, credential-redacting Runtime v1 HTTP boundary. */
@RestController
@RequestMapping("/agent/runtime/v1")
@RequiredArgsConstructor
public class AgentRuntimeV1Controller {
    private static final String CACHE_CONTROL = "private, no-store";
    private final AgentRuntimeV1Service runtime;

    @PostMapping("/installations")
    public ResponseEntity<JsonResult<AgentRuntimeV1InstallationView>> create(
            @RequestBody AgentRuntimeV1InstallationRequest request, Authentication authentication,
            HttpServletRequest servletRequest) {
        rejectLegacyApiKey(servletRequest);
        var scope = AgentController.requireJwtScope(authentication);
        return ok(runtime.create(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), request, now()));
    }

    @GetMapping("/installations/{installationId}")
    public ResponseEntity<JsonResult<AgentRuntimeV1InstallationView>> status(
            @PathVariable String installationId, Authentication authentication, HttpServletRequest servletRequest) {
        rejectLegacyApiKey(servletRequest);
        var scope = AgentController.requireJwtScope(authentication);
        return ok(runtime.status(scope.tenantId(), scope.clientId(), installationId));
    }

    @PostMapping("/installations/{installationId}/revoke")
    public ResponseEntity<JsonResult<Void>> revoke(@PathVariable String installationId,
            Authentication authentication, HttpServletRequest servletRequest) {
        rejectLegacyApiKey(servletRequest);
        var scope = AgentController.requireJwtScope(authentication);
        runtime.revoke(scope.tenantId(), scope.clientId(), installationId, now());
        return ok(null);
    }

    @PostMapping("/enroll")
    public ResponseEntity<JsonResult<AgentRuntimeV1EnrollmentResult>> enroll(
            @RequestBody AgentRuntimeV1EnrollmentRequest request, HttpServletRequest servletRequest) {
        rejectLegacyApiKey(servletRequest);
        // This installer-only response is intentionally the sole Runtime v1 response containing authorization material.
        return ok(runtime.enroll(request, now()));
    }

    @PostMapping("/session")
    public ResponseEntity<JsonResult<AgentRuntimeV1InstallationView>> session(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AgentRuntimeV1RuntimeRequest request, HttpServletRequest servletRequest) {
        rejectLegacyApiKey(servletRequest);
        return ok(runtime.session(bearer(authorization), request, now()));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<JsonResult<AgentRuntimeV1InstallationView>> heartbeat(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AgentRuntimeV1RuntimeRequest request, HttpServletRequest servletRequest) {
        rejectLegacyApiKey(servletRequest);
        return ok(runtime.heartbeat(bearer(authorization), request, now()));
    }

    @PostMapping("/commands/{messageId}/acks")
    public ResponseEntity<JsonResult<AgentCommandAckResult>> acknowledge(@PathVariable String messageId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AgentRuntimeV1AckRequest request, HttpServletRequest servletRequest) {
        rejectLegacyApiKey(servletRequest);
        return ok(runtime.acknowledge(bearer(authorization), messageId, request, now()));
    }

    @ExceptionHandler(AgentBizException.class)
    public ResponseEntity<JsonResult<Void>> rejected(AgentBizException ignored) {
        // Do not log or expose request/body/header data on installer/runtime failures.
        JsonResult<Void> result = JsonResult.failure("AGENT_FORBIDDEN", "Runtime v1 request rejected");
        result.setStatus(HttpStatus.FORBIDDEN.value());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<JsonResult<Void>> invalid(IllegalArgumentException ignored) {
        JsonResult<Void> result = JsonResult.failure("BAD_REQUEST", "Runtime v1 request rejected");
        result.setStatus(HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(result);
    }

    private static String bearer(String header) {
        if (header == null || !header.startsWith("Bearer ") || header.length() <= "Bearer ".length()
                || header.indexOf(',', "Bearer ".length()) >= 0) {
            throw new AgentBizException("AGENT_FORBIDDEN", "Runtime v1 authorization rejected");
        }
        return header.substring("Bearer ".length());
    }
    private static void rejectLegacyApiKey(HttpServletRequest request) {
        if (request != null && (request.getParameter("api_key") != null || request.getParameter("apiKey") != null)) {
            throw new AgentBizException("AGENT_FORBIDDEN", "Runtime v1 URL credentials are forbidden");
        }
    }
    private static long now() { return System.currentTimeMillis(); }
    private static <T> ResponseEntity<JsonResult<T>> ok(T data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(JsonResult.success(data));
    }
}
