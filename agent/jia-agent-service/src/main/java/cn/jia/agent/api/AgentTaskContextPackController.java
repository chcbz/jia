package cn.jia.agent.api;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.entity.AgentTaskContextPackDTO;
import cn.jia.agent.exception.AgentTaskContextPackException;
import cn.jia.agent.service.AgentTaskContextPackService;
import cn.jia.core.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Authenticated F01 retrieval. JWT claims are the sole tenant/client scope authority. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskContextPackController {
    static final int MAX_SERIALIZED_BYTES = 1024 * 1024;
    static final String CACHE_CONTROL_VALUE = "private, no-store";

    private final AgentTaskContextPackService contextPackService;
    private final AgentTaskEventsGate taskEventsGate;

    @GetMapping(value = "/{taskId}/context-pack", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> contextPack(@PathVariable String taskId,
            HttpServletRequest request, Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireId(taskId, "taskId", 100);
        String actorAgentId = singleRequiredQuery(request, "actorAgentId");
        requireId(actorAgentId, "actorAgentId", 100);
        String expectedVersion = singleOptionalQuery(request, "expectedVersion");
        if (expectedVersion != null && !canonicalDecimal(expectedVersion)) {
            throw new ContextPackRequestException("expectedVersion");
        }
        if (!taskEventsGate.allows(scope.tenantId(), scope.clientId())) {
            throw new AgentTaskContextPackException(
                    AgentTaskContextPackException.Reason.CONTEXT_UNAVAILABLE);
        }
        AgentTaskContextPackDTO pack = contextPackService.generate(
                scope.tenantId(), scope.clientId(), taskId, actorAgentId, expectedVersion);
        final byte[] body;
        try {
            body = JsonUtil.getMapper().writeValueAsBytes(pack);
        } catch (Exception exception) {
            throw new AgentTaskContextPackException(
                    AgentTaskContextPackException.Reason.CONTEXT_UNAVAILABLE, exception);
        }
        if (body.length > MAX_SERIALIZED_BYTES) {
            throw new AgentTaskContextPackException(
                    AgentTaskContextPackException.Reason.CONTEXT_UNAVAILABLE);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }

    @ExceptionHandler(ContextPackAuthenticationException.class)
    public ResponseEntity<ContextPackError> handleAuthentication(
            ContextPackAuthenticationException exception) {
        HttpStatus status = exception.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        return error(status,
                exception.forbidden ? "CONTEXT_PACK_FORBIDDEN" : "CONTEXT_PACK_UNAUTHENTICATED",
                exception.forbidden ? "Context Pack scope is unavailable"
                        : "Authentication is required");
    }

    @ExceptionHandler(ContextPackRequestException.class)
    public ResponseEntity<ContextPackError> handleBadRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid Context Pack request");
    }

    @ExceptionHandler(AgentTaskContextPackException.class)
    public ResponseEntity<ContextPackError> handleContextFailure(
            AgentTaskContextPackException exception) {
        return switch (exception.getReason()) {
            case NOT_FOUND_OR_FORBIDDEN -> {
                log.warn("Context Pack request rejected: reason=not_found_or_forbidden");
                yield error(HttpStatus.NOT_FOUND, "TASK_CONTEXT_PACK_NOT_FOUND",
                        "Task Context Pack is not available in the requested scope");
            }
            case STALE_VERSION -> error(HttpStatus.CONFLICT, "TASK_CONTEXT_PACK_STALE",
                    "Task Context Pack version is stale");
            case CONTEXT_UNAVAILABLE -> {
                log.error("Context Pack request failed: reason=context_unavailable");
                yield error(HttpStatus.SERVICE_UNAVAILABLE, "TASK_CONTEXT_PACK_UNAVAILABLE",
                        "Task Context Pack is temporarily unavailable");
            }
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ContextPackError> handleUnexpected(Exception ignored) {
        log.error("Context Pack request failed: reason=unexpected");
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TASK_CONTEXT_PACK_UNAVAILABLE",
                "Task Context Pack is temporarily unavailable");
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new ContextPackAuthenticationException(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object tenantId = claims.get("jiacn");
        Object clientId = claims.get("client_id");
        if (!(tenantId instanceof String tenant) || !(clientId instanceof String client)
                || !validExact(tenant, 50) || !validExact(client, 50)) {
            throw new ContextPackAuthenticationException(true);
        }
        return new Scope(tenant, client);
    }

    private static String singleRequiredQuery(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1) {
            throw new ContextPackRequestException(name);
        }
        return values[0];
    }

    private static String singleOptionalQuery(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1) {
            throw new ContextPackRequestException(name);
        }
        return values[0];
    }

    private static void requireId(String value, String field, int maxCodePoints) {
        if (!validExact(value, maxCodePoints)) {
            throw new ContextPackRequestException(field);
        }
    }

    private static boolean canonicalDecimal(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            return false;
        }
        try {
            return Long.toString(Long.parseLong(value)).equals(value);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean validExact(String value, int maxCodePoints) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxCodePoints
                && !value.codePoints().allMatch(AgentTaskContextPackController::isPadding)
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static ResponseEntity<ContextPackError> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ContextPackError(code, message));
    }

    private record Scope(String tenantId, String clientId) {
    }

    public record ContextPackError(String code, String message) {
    }

    private static final class ContextPackAuthenticationException extends RuntimeException {
        private final boolean forbidden;

        private ContextPackAuthenticationException(boolean forbidden) {
            this.forbidden = forbidden;
        }
    }

    private static final class ContextPackRequestException extends RuntimeException {
        private ContextPackRequestException(String field) {
            super(field);
        }
    }
}
