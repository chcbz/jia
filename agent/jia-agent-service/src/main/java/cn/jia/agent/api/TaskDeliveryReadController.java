package cn.jia.agent.api;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.TaskDeliveryQueryService;
import cn.jia.agent.output.dto.TaskDeliveryPageDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent/tasks/{taskId}/deliveries")
@ConditionalOnProperty(prefix = "agent.output-delivery", name = "enabled", havingValue = "true")
public final class TaskDeliveryReadController {
    private final TaskDeliveryQueryService service;

    public TaskDeliveryReadController(TaskDeliveryQueryService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OutputHttpEnvelope.Success<TaskDeliveryPageDTO>> list(
            @PathVariable String taskId, Authentication authentication,
            HttpServletRequest request) {
        OutputHttpSupport.Scope scope = OutputHttpSupport.scope(authentication);
        TaskDeliveryPageDTO page = service.list(
                scope.tenantId(), scope.clientId(), scope.jiacn(), taskId,
                OutputHttpSupport.optionalQuery(request, "cursor"),
                OutputHttpSupport.optionalLimit(request));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(OutputHttpEnvelope.REQUEST_ID_HEADER,
                        OutputHttpEnvelope.requestId(request))
                .body(new OutputHttpEnvelope.Success<>(page));
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OutputHttpEnvelope.Error> unavailable(
            Exception failure, HttpServletRequest request) {
        return OutputHttpEnvelope.error(request, "OUTPUT_DELIVERY_UNAVAILABLE",
                "Formal delivery unavailable", 503, true);
    }
}
