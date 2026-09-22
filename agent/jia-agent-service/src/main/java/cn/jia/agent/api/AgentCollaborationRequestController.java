package cn.jia.agent.api;

import cn.jia.agent.service.AgentCollaborationRequestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** Explicit direct-@ / private-meeting execution entry; it never routes through Song Jiang. */
@RestController
@RequestMapping("/agent/hall/collaboration")
public class AgentCollaborationRequestController {
    private static final String CACHE_CONTROL = "private, no-store";
    private final AgentCollaborationRequestService service;

    public AgentCollaborationRequestController(AgentCollaborationRequestService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping(value = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentCollaborationRequestService.RequestView> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request, Authentication authentication) {
        HallRequestDraftController.requireNoQuery(request);
        CreateRequest body = HallRequestDraftController.parse(
                HallRequestDraftController.readBounded(request), CreateRequest.class);
        if (body == null || body.inputs() == null) throw new RequestFailure();
        return ResponseEntity.status(HttpStatus.ACCEPTED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(service.submit(scope(authentication),
                        new AgentCollaborationRequestService.RequestCommand(body.conversationId(),
                                body.targetAgentId(), body.instruction(), body.outputContentMimeType(),
                                body.inputs().stream().map(input -> {
                                    if (input == null || input.version() == null) throw new RequestFailure();
                                    return new AgentCollaborationRequestService.InputSelection(input.fileId(), input.version());
                                }).toList()), key));
    }

    @GetMapping(value = "/requests/{requestId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentCollaborationRequestService.RequestView> get(
            @PathVariable String requestId, HttpServletRequest request, Authentication authentication) {
        HallRequestDraftController.requireNoQuery(request);
        return ok(service.get(scope(authentication), requestId));
    }

    @GetMapping(value = "/requests/request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentCollaborationRequestService.RequestView> request(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request, Authentication authentication) {
        HallRequestDraftController.requireNoQuery(request);
        return ok(service.getByIdempotencyKey(scope(authentication), key));
    }

    @ExceptionHandler(HallRequestDraftController.AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(HallRequestDraftController.AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "COLLABORATION_FORBIDDEN" : "COLLABORATION_UNAUTHENTICATED",
                "Collaboration access is unavailable");
    }

    @ExceptionHandler(AgentCollaborationRequestService.Failure.class)
    public ResponseEntity<ErrorBody> failure(AgentCollaborationRequestService.Failure failure) {
        return switch (failure.reason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "COLLABORATION_BAD_REQUEST",
                    "Invalid collaboration request");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "COLLABORATION_NOT_FOUND",
                    "Collaboration resource is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "COLLABORATION_IDEMPOTENCY_CONFLICT",
                    "Operation key conflicts with a different request");
            case CAPABILITY_UNAVAILABLE -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "COLLABORATION_CAPABILITY_UNAVAILABLE", "Requested execution capability is unavailable");
            case STATE_CONFLICT -> error(HttpStatus.CONFLICT, "COLLABORATION_STATE_CONFLICT",
                    "Collaboration request state changed");
            case STORAGE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "COLLABORATION_UNAVAILABLE", "Collaboration is temporarily unavailable");
        };
    }

    @ExceptionHandler({HallRequestDraftController.RequestFailure.class, RequestFailure.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "COLLABORATION_BAD_REQUEST", "Invalid collaboration request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "COLLABORATION_UNAVAILABLE",
                "Collaboration is temporarily unavailable");
    }

    private static AgentCollaborationRequestService.OwnerScope scope(Authentication authentication) {
        var owner = HallRequestDraftController.scope(authentication);
        return new AgentCollaborationRequestService.OwnerScope(owner.tenantId(), owner.clientId(), owner.ownerJiacn());
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(code, message));
    }

    public record CreateRequest(String conversationId, String targetAgentId, String instruction,
            String outputContentMimeType, List<InputRequest> inputs) { }
    public record InputRequest(String fileId, Integer version) { }
    public record ErrorBody(String code, String message) { }
    private static final class RequestFailure extends RuntimeException { }
}
