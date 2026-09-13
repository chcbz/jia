package cn.jia.agent.api;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.TaskDeliveryHttpResult;
import cn.jia.agent.output.TaskDeliverySubmissionService;
import cn.jia.agent.output.dto.TaskDeliveryItemDTO;
import cn.jia.agent.output.dto.TaskDeliverySubmitDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/agent/tasks/{taskId}/deliveries")
@ConditionalOnProperty(prefix = "agent.output-delivery", name = "enabled", havingValue = "true")
public final class TaskDeliveryController {
    private static final int MAX_BODY_BYTES = 300_000;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> ALLOWED = Set.of(
            "runId", "workItemId", "expectedTaskVersion", "expectedWorkItemVersion",
            "leaseToken", "summary", "items");
    private static final Set<String> REQUIRED = ALLOWED;
    private static final Set<String> ITEM_ALLOWED = Set.of("artifactId", "version", "purpose");
    private static final Set<String> ITEM_REQUIRED = Set.of("artifactId", "version");

    private final TaskDeliverySubmissionService service;

    public TaskDeliveryController(TaskDeliverySubmissionService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submit(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable String taskId, @RequestBody byte[] body,
            Authentication authentication, HttpServletRequest request) {
        TaskDeliveryHttpResult result = service.submit(
                ticket(authentication), bearer, idempotencyKey, taskId,
                parse(body), OutputHttpEnvelope.requestId(request));
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(OutputHttpEnvelope.REQUEST_ID_HEADER,
                        OutputHttpEnvelope.requestId(request))
                .body(result.responseJson());
    }

    @ExceptionHandler(OutputAuthorizationException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> authorization(
            OutputAuthorizationException failure, HttpServletRequest request) {
        int status = "OUTPUT_AUTH_UNAUTHORIZED".equals(failure.getCode()) ? 401 : 403;
        return OutputHttpEnvelope.error(request, failure.getCode(),
                "Output access is unavailable", status, false);
    }

    @ExceptionHandler(OutputDeliveryException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> delivery(
            OutputDeliveryException failure, HttpServletRequest request) {
        return OutputHttpEnvelope.error(request, failure.code(), failure.getMessage(),
                failure.status(), failure.retryable());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> missingHeader(
            MissingRequestHeaderException failure, HttpServletRequest request) {
        return OutputHttpEnvelope.error(request, "OUTPUT_REQUEST_INVALID",
                "Invalid formal delivery request", 400, false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OutputHttpEnvelope.Error> unavailable(
            Exception failure, HttpServletRequest request) {
        return OutputHttpEnvelope.error(request, "OUTPUT_SUBMISSION_UNAVAILABLE",
                "Formal delivery unavailable", 503, true);
    }

    static TaskDeliverySubmitDTO parse(byte[] body) {
        if (body == null || body.length == 0) throw bad();
        if (body.length > MAX_BODY_BYTES) {
            throw new OutputDeliveryException("OUTPUT_REQUEST_TOO_LARGE",
                    "Formal delivery request is too large", 413, false);
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) throw bad();
            Set<String> fields = new HashSet<>(root.propertyNames());
            if (!ALLOWED.containsAll(fields) || !fields.containsAll(REQUIRED)) throw bad();
            JsonNode itemNodes = root.get("items");
            if (itemNodes == null || !itemNodes.isArray()) throw bad();
            List<TaskDeliveryItemDTO> items = new ArrayList<>();
            for (JsonNode item : itemNodes) {
                if (item == null || !item.isObject()) throw bad();
                Set<String> itemFields = new HashSet<>(item.propertyNames());
                if (!ITEM_ALLOWED.containsAll(itemFields)
                        || !itemFields.containsAll(ITEM_REQUIRED)) throw bad();
                items.add(new TaskDeliveryItemDTO(
                        text(item, "artifactId"), text(item, "version"),
                        optionalText(item, "purpose")));
            }
            return new TaskDeliverySubmitDTO(
                    text(root, "runId"), text(root, "workItemId"),
                    text(root, "expectedTaskVersion"),
                    text(root, "expectedWorkItemVersion"),
                    text(root, "leaseToken"), text(root, "summary"), List.copyOf(items));
        } catch (OutputDeliveryException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw bad();
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString()) throw bad();
        return value.asText();
    }

    private static String optionalText(JsonNode node, String name) {
        return node.has(name) ? text(node, name) : null;
    }

    private static OutputTicketAuthorization ticket(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OutputTicketAuthorization ticket)) {
            throw new OutputAuthorizationException(
                    "OUTPUT_AUTH_UNAUTHORIZED", "Output ticket is required");
        }
        return ticket;
    }

    private static OutputDeliveryException bad() {
        return new OutputDeliveryException("OUTPUT_REQUEST_INVALID",
                "Invalid formal delivery request", 400, false);
    }
}
