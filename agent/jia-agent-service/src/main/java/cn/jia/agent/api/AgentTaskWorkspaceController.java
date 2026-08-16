package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.core.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Browser-facing C04 workspace endpoint. JWT claims are the sole scope authority. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskWorkspaceController {
    static final int MAX_SERIALIZED_BYTES = 4 * 1024 * 1024;
    static final String CACHE_CONTROL_VALUE = "private, no-store";

    private final AgentTaskWorkspaceService workspaceService;

    @GetMapping(value = "/{taskId}/workspace", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> workspace(@PathVariable String taskId,
            @RequestParam String actorAgentId, Authentication authentication) {
        requireRequestId(taskId, "taskId", 100);
        requireRequestId(actorAgentId, "actorAgentId", 100);
        Scope scope = requireJwtScope(authentication);
        AgentTaskWorkspaceDTO snapshot = workspaceService.snapshot(
                scope.jiacn(), scope.clientId(), taskId, actorAgentId);
        byte[] body;
        try {
            body = JsonUtil.getMapper().writeValueAsBytes(snapshot);
        } catch (Exception exception) {
            throw new AgentTaskWorkspaceException(
                    AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE, exception);
        }
        if (body.length > MAX_SERIALIZED_BYTES) {
            throw new AgentTaskWorkspaceException(
                    AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }

    @ExceptionHandler(WorkspaceAuthenticationException.class)
    public ResponseEntity<WorkspaceError> handleAuthentication(
            WorkspaceAuthenticationException exception) {
        HttpStatus status = exception.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        return error(status, exception.forbidden ? "WORKSPACE_FORBIDDEN" : "WORKSPACE_UNAUTHENTICATED",
                exception.forbidden ? "Workspace scope is unavailable"
                        : "Authentication is required");
    }

    @ExceptionHandler({WorkspaceRequestException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<WorkspaceError> handleBadRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid workspace request");
    }

    @ExceptionHandler(AgentTaskWorkspaceException.class)
    public ResponseEntity<WorkspaceError> handleWorkspaceFailure(
            AgentTaskWorkspaceException exception) {
        if (exception.getReason()
                == AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN) {
            log.warn("Workspace request rejected: reason=not_found_or_forbidden");
            return error(HttpStatus.NOT_FOUND, "TASK_WORKSPACE_NOT_FOUND",
                    "Task workspace is not available in the requested scope");
        }
        log.error("Workspace request failed: reason=snapshot_unavailable");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TASK_WORKSPACE_UNAVAILABLE",
                "Task workspace snapshot is temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<WorkspaceError> handleUnexpected(Exception ignored) {
        log.error("Workspace request failed: reason=unexpected");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TASK_WORKSPACE_UNAVAILABLE",
                "Task workspace snapshot is temporarily unavailable");
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new WorkspaceAuthenticationException(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object jiacn = claims.get("jiacn");
        Object clientId = claims.get("client_id");
        if (!(jiacn instanceof String tenant) || !(clientId instanceof String client)
                || !validExact(tenant, 50) || !validExact(client, 50)) {
            throw new WorkspaceAuthenticationException(true);
        }
        return new Scope(tenant, client);
    }

    private static void requireRequestId(String value, String name, int maxLength) {
        if (!validExact(value, maxLength)) {
            throw new WorkspaceRequestException(name);
        }
    }

    private static boolean validExact(String value, int maxLength) {
        return value != null && value.length() <= maxLength
                && !value.codePoints().allMatch(AgentTaskWorkspaceController::isPadding)
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static ResponseEntity<WorkspaceError> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new WorkspaceError(code, message));
    }

    private record Scope(String jiacn, String clientId) {
    }

    public record WorkspaceError(String code, String message) {
    }

    private static final class WorkspaceAuthenticationException extends RuntimeException {
        private final boolean forbidden;

        private WorkspaceAuthenticationException(boolean forbidden) {
            this.forbidden = forbidden;
        }
    }

    private static final class WorkspaceRequestException extends RuntimeException {
        private WorkspaceRequestException(String field) {
            super(field);
        }
    }
}
