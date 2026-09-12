package cn.jia.agent.api;

import cn.jia.agent.entity.AgentWorkItemPlanConfirmRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanSuggestRequestDTO;
import cn.jia.agent.entity.AgentWorkItemPlanViewDTO;
import cn.jia.agent.exception.AgentWorkItemPlanException;
import cn.jia.agent.service.AgentWorkItemPlanService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * E03 API catalog: suggest is read-only; confirm is the sole persistence boundary.
 * The controller stays disabled unless the deployment explicitly enables the feature.
 */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
@ConditionalOnProperty(prefix = "jia.agent.work-item-plan", name = "enabled", havingValue = "true")
public class AgentWorkItemPlanController {
    static final String CACHE_CONTROL = "private, no-store";
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentWorkItemPlanService planService;

    public AgentWorkItemPlanController(AgentWorkItemPlanService planService) {
        this.planService = Objects.requireNonNull(planService, "planService");
    }

    @PostMapping(value = "/{taskId}/work-item-plans/suggest",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemPlanViewDTO> suggest(
            @PathVariable String taskId,
            @RequestBody byte[] body,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        String actorAgentId = actor(request);
        requireExact(taskId, 100);
        AgentWorkItemPlanSuggestRequestDTO command = parse(
                body, AgentWorkItemPlanSuggestRequestDTO.class);
        return ok(planService.suggest(
                scope.tenantId(), scope.clientId(), taskId, actorAgentId, command));
    }

    @PostMapping(value = "/{taskId}/work-item-plans/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemPlanViewDTO> confirm(
            @PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        String actorAgentId = actor(request);
        requireExact(taskId, 100);
        requireIdempotencyKey(idempotencyKey);
        AgentWorkItemPlanConfirmRequestDTO command = parse(
                body, AgentWorkItemPlanConfirmRequestDTO.class);
        return ok(planService.confirm(scope.tenantId(), scope.clientId(), taskId,
                actorAgentId, idempotencyKey, command));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "WORK_ITEM_PLAN_FORBIDDEN" : "WORK_ITEM_PLAN_UNAUTHENTICATED",
                "Work item planning access is unavailable");
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(RequestFailure ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid work item plan request");
    }

    @ExceptionHandler(AgentWorkItemPlanException.class)
    public ResponseEntity<ErrorBody> planFailure(AgentWorkItemPlanException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "Invalid work item plan request");
            case NOT_FOUND_OR_FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    "WORK_ITEM_PLAN_NOT_FOUND", "Work item plan is unavailable");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT,
                    "WORK_ITEM_PLAN_STALE", "Task planning state changed");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT,
                    "WORK_ITEM_PLAN_CONFLICT", "Idempotency key was already used differently");
            case INVALID_PERSISTED_STATE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "WORK_ITEM_PLAN_UNAVAILABLE", "Work item planning is temporarily unavailable");
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) {
        log.error("Work item plan request failed: uri={}", request.getRequestURI());
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "WORK_ITEM_PLAN_UNAVAILABLE", "Work item planning is temporarily unavailable");
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object tenant = claims.get("jiacn");
        Object client = claims.get("client_id");
        if (!(tenant instanceof String tenantId) || !(client instanceof String clientId)
                || !exact(tenantId, 50) || !exact(clientId, 50)) {
            throw new AuthenticationFailure(true);
        }
        return new Scope(tenantId, clientId);
    }

    private static String actor(HttpServletRequest request) {
        String[] values = request.getParameterValues("actorAgentId");
        if (values == null || values.length != 1 || !exact(values[0], 100)) {
            throw new RequestFailure();
        }
        return values[0];
    }

    private static void requireExact(String value, int maxLength) {
        if (!exact(value, maxLength)) {
            throw new RequestFailure();
        }
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~:/+\\-]{8,128}")) {
            throw new RequestFailure();
        }
    }

    private static <T> T parse(byte[] body, Class<T> type) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new RequestFailure();
        }
        try {
            T value = STRICT_JSON.readValue(body, type);
            if (value == null) {
                throw new RequestFailure();
            }
            return value;
        } catch (RequestFailure failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
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

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
    }

    private static ResponseEntity<ErrorBody> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    private record Scope(String tenantId, String clientId) {
    }

    public record ErrorBody(String code, String message) {
    }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;

        private AuthenticationFailure(boolean forbidden) {
            this.forbidden = forbidden;
        }
    }

    private static final class RequestFailure extends RuntimeException {
    }
}
