package cn.jia.agent.api;

import cn.jia.agent.service.AgentTaskCreationOperationService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Raw no-store browser contract for one atomic ordinary-task creation operation. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks/creation-operations")
public final class AgentTaskCreationOperationController {
    static final String CACHE_CONTROL = "private, no-store";
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "title", "description", "requiredAbilities", "reward", "inputRefs");
    private static final Set<String> REFERENCE_FIELDS = Set.of("fileId", "version", "purpose");

    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final AgentTaskCreationOperationService operations;

    public AgentTaskCreationOperationController(AgentTaskCreationOperationService operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskCreationOperationService.Receipt> create(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        AgentTaskCreationOperationService.Scope scope = scope(authentication);
        requireSingleKeyAndNoQuery(request, idempotencyKey);
        AgentTaskCreationOperationService.CreateCommand command = command(rawBody);
        AgentTaskCreationOperationService.Result result =
                operations.create(scope, idempotencyKey, command);
        if (result == null || result.receipt() == null) {
            throw new AgentTaskCreationOperationService.Failure(
                    AgentTaskCreationOperationService.Reason.UNAVAILABLE);
        }
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(result.receipt());
    }

    @GetMapping(value = "/request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskCreationOperationService.Receipt> request(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        AgentTaskCreationOperationService.Scope scope = scope(authentication);
        requireSingleKeyAndNoQuery(request, idempotencyKey);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(operations.getByIdempotencyKey(scope, idempotencyKey));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "TASK_CREATION_FORBIDDEN" : "UNAUTHENTICATED",
                failure.forbidden ? "Task creation scope is unavailable"
                        : "Authentication is required");
    }

    @ExceptionHandler(AgentTaskCreationOperationService.Failure.class)
    public ResponseEntity<ErrorBody> failure(AgentTaskCreationOperationService.Failure failure) {
        return switch (failure.reason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "Invalid task creation request");
            case FORBIDDEN -> error(HttpStatus.FORBIDDEN, "TASK_CREATION_FORBIDDEN",
                    "Task creation scope is unavailable");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "TASK_CREATION_NOT_FOUND",
                    "Task creation operation is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency key conflicts with a different request");
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "TASK_CREATION_UNAVAILABLE", "Task creation is temporarily unavailable");
        };
    }

    @ExceptionHandler({InvalidRequest.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid task creation request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception failure) {
        log.error("Task creation operation failed: type={}", failure.getClass().getName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TASK_CREATION_UNAVAILABLE",
                "Task creation is temporarily unavailable");
    }

    private AgentTaskCreationOperationService.CreateCommand command(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) throw new InvalidRequest();
        try {
            JsonNode root = STRICT_JSON.readTree(rawBody);
            if (root == null || !root.isObject() || !root.has("title")) {
                throw new InvalidRequest();
            }
            Set<String> fields = fieldNames(root);
            if (!REQUEST_FIELDS.containsAll(fields) || !root.get("title").isTextual()) {
                throw new InvalidRequest();
            }
            String title = root.get("title").textValue();
            boolean descriptionPresent = root.has("description");
            String description = nullableText(root.get("description"), descriptionPresent);
            boolean abilitiesPresent = root.has("requiredAbilities");
            List<String> abilities = abilities(root.get("requiredAbilities"), abilitiesPresent);
            boolean rewardPresent = root.has("reward");
            Integer reward = integer(root.get("reward"), rewardPresent);
            List<AgentTaskCreationOperationService.InputReference> references =
                    references(root.get("inputRefs"), root.has("inputRefs"));
            return new AgentTaskCreationOperationService.CreateCommand(title,
                    descriptionPresent, description, abilitiesPresent, abilities,
                    rewardPresent, reward, references);
        } catch (InvalidRequest failure) {
            throw failure;
        } catch (Exception failure) {
            throw new InvalidRequest();
        }
    }

    private static String nullableText(JsonNode value, boolean present) {
        if (!present || value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new InvalidRequest();
        return value.textValue();
    }

    private static List<String> abilities(JsonNode value, boolean present) {
        if (!present || value == null || value.isNull()) return null;
        if (!value.isArray()) throw new InvalidRequest();
        List<String> abilities = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new InvalidRequest();
            abilities.add(item.textValue());
        }
        return List.copyOf(abilities);
    }

    private static Integer integer(JsonNode value, boolean present) {
        if (!present || value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new InvalidRequest();
        return value.intValue();
    }

    private static List<AgentTaskCreationOperationService.InputReference> references(
            JsonNode value, boolean present) {
        if (!present) return List.of();
        if (value == null || !value.isArray() || value.size() > 32) throw new InvalidRequest();
        List<AgentTaskCreationOperationService.InputReference> references = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isObject() || !fieldNames(item).equals(REFERENCE_FIELDS)
                    || !item.get("fileId").isTextual()
                    || !item.get("version").isIntegralNumber()
                    || !item.get("version").canConvertToInt()
                    || !item.get("purpose").isTextual()) {
                throw new InvalidRequest();
            }
            String fileId = item.get("fileId").textValue();
            int version = item.get("version").intValue();
            String purpose = item.get("purpose").textValue();
            if (version < 1 || !"REFERENCE".equals(purpose)
                    || !selected.add(fileId + "\u0000" + version + "\u0000" + purpose)) {
                throw new InvalidRequest();
            }
            references.add(new AgentTaskCreationOperationService.InputReference(
                    fileId, version, purpose));
        }
        return List.copyOf(references);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static AgentTaskCreationOperationService.Scope scope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object owner = claims.get("jiacn");
        Object client = claims.get("client_id");
        final String tenant;
        try {
            tenant = TenantClaimPolicy.resolve(claims, "0");
        } catch (IllegalArgumentException invalid) {
            throw new AuthenticationFailure(true);
        }
        EsContext context = EsContextHolder.getContext();
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !valid(ownerJiacn, 50) || "0".equals(ownerJiacn)
                || !valid(clientId, 50) || !"0".equals(tenant)
                || context == null || !"0".equals(context.getTenantId())
                || !Objects.equals(ownerJiacn, context.getJiacn())
                || !Objects.equals(clientId, context.getClientId())) {
            throw new AuthenticationFailure(true);
        }
        return new AgentTaskCreationOperationService.Scope("0", clientId, ownerJiacn);
    }

    private static void requireSingleKeyAndNoQuery(
            HttpServletRequest request, String idempotencyKey) {
        if (!valid(idempotencyKey, 100) || !request.getParameterMap().isEmpty()) {
            throw new InvalidRequest();
        }
        List<String> values = Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1 || !Objects.equals(idempotencyKey, values.getFirst())) {
            throw new InvalidRequest();
        }
    }

    private static boolean valid(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private static ResponseEntity<ErrorBody> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    public record ErrorBody(String code, String message) { }
    private static final class InvalidRequest extends RuntimeException { }
    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }
}
