package cn.jia.agent.api;

import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputLeaseHttpResult;
import cn.jia.agent.output.OutputLeaseService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.dto.OutputLeaseRequestDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
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

import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/agent/tasks/{taskId}/work-items/{workItemId}/lease")
@ConditionalOnProperty(prefix = "agent.output-delivery", name = "enabled", havingValue = "true")
public class OutputLeaseController {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> ALLOWED = Set.of(
            "runId", "expectedVersion", "leaseToken", "leaseDurationMillis");
    private static final Set<String> BASE_REQUIRED = Set.of("runId", "expectedVersion");

    private final OutputLeaseService service;

    public OutputLeaseController(OutputLeaseService service) {
        this.service = service;
    }

    @PostMapping(value = "/{action}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> mutate(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable String taskId, @PathVariable String workItemId,
            @PathVariable String action, @RequestBody byte[] body,
            Authentication authentication, HttpServletRequest servletRequest) {
        OutputLeaseHttpResult result = service.mutate(
                ticket(authentication), bearer, key, taskId, workItemId, action,
                parse(action, body), OutputHttpEnvelope.requestId(servletRequest));
        return response(result, servletRequest);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> recover(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String bearer,
            @PathVariable String taskId, @PathVariable String workItemId,
            Authentication authentication, HttpServletRequest servletRequest) {
        OutputLeaseHttpResult result = service.recover(
                ticket(authentication), bearer, taskId, workItemId,
                OutputHttpEnvelope.requestId(servletRequest));
        return response(result, servletRequest);
    }

    @ExceptionHandler(OutputAuthorizationException.class)
    public ResponseEntity<OutputHttpEnvelope.Error> authorization(
            OutputAuthorizationException failure, HttpServletRequest request) {
        int status = "OUTPUT_AUTH_UNAUTHORIZED".equals(failure.getCode()) ? 401 : 403;
        return OutputHttpEnvelope.error(request, failure.getCode(),
                "Output access is unavailable", status, false);
    }

    @ExceptionHandler({AgentTaskStateException.class, MissingRequestHeaderException.class})
    public ResponseEntity<OutputHttpEnvelope.Error> badRequest(
            Exception failure, HttpServletRequest request) {
        return OutputHttpEnvelope.error(request, "OUTPUT_REQUEST_INVALID",
                "Invalid work lease request", 400, false);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OutputHttpEnvelope.Error> unavailable(
            Exception failure, HttpServletRequest request) {
        return OutputHttpEnvelope.error(request, "OUTPUT_LEASE_UNAVAILABLE",
                "Work lease is unavailable", 503, true);
    }

    private static OutputTicketAuthorization ticket(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OutputTicketAuthorization ticket)) {
            throw new OutputAuthorizationException(
                    "OUTPUT_AUTH_UNAUTHORIZED", "Output ticket is required");
        }
        return ticket;
    }

    private static OutputLeaseRequestDTO parse(String action, byte[] body) {
        if (body == null || body.length == 0 || body.length > 4096) throw bad();
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw bad();
            Set<String> fields = new HashSet<>(node.propertyNames());
            if (!ALLOWED.containsAll(fields) || !fields.containsAll(BASE_REQUIRED)) throw bad();
            Set<String> expected = switch (action) {
                case "claim" -> Set.of("runId", "expectedVersion", "leaseDurationMillis");
                case "start", "release" -> Set.of("runId", "expectedVersion", "leaseToken");
                case "heartbeat" -> ALLOWED;
                default -> throw bad();
            };
            if (!fields.equals(expected)) throw bad();
            return new OutputLeaseRequestDTO(
                    text(node, "runId"), text(node, "expectedVersion"),
                    optionalText(node, "leaseToken"),
                    optionalText(node, "leaseDurationMillis"));
        } catch (AgentTaskStateException failure) {
            throw failure;
        } catch (Exception invalid) {
            throw bad();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw bad();
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        return node.has(field) ? text(node, field) : null;
    }

    private static AgentTaskStateException bad() {
        return new AgentTaskStateException(
                AgentTaskStateException.Reason.INVALID_REQUEST,
                "Invalid work lease request");
    }

    private static ResponseEntity<String> response(
            OutputLeaseHttpResult result, HttpServletRequest request) {
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(OutputHttpEnvelope.REQUEST_ID_HEADER,
                        OutputHttpEnvelope.requestId(request))
                .body(result.responseJson());
    }
}
