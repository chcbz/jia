package cn.jia.chat.api;

import cn.jia.chat.service.ChatDeliveryReviewContract;
import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import cn.jia.chat.service.ChatTaskDeliveryReviewService;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.config.ApiJsonInput;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/agent/tasks/{taskId}/delivery-review-operations")
@ConditionalOnProperty(prefix = "chat.delivery-review", name = "enabled", havingValue = "true")
public final class AgentTaskDeliveryReviewController {
    private final ChatTaskDeliveryReviewService service;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;
    // Derived from the ACCEPT-only schema: <=100 escaped ID units, five decimal values,
    // hash/enums/null fields and fixed JSON syntax; normalization allows one whitespace per byte.
    static final int MAX_BODY = 2 * (6 * 100 + 5 * 19 + 64 + 512) + 1;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public AgentTaskDeliveryReviewController(ChatTaskDeliveryReviewService service, HumanSenderIdentityResolver identities, TenantScopeResolver tenants) {
        this.service = service; this.identities = identities; this.tenants = tenants;
    }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<Receipt>> accept(@PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest request, Authentication authentication) {
        Scope scope = scope(authentication); noQuery(request);
        Accept command;
        try { command = ChatDeliveryReviewContract.parse(JSON.readTree(ApiJsonInput.readData(request, MAX_BODY, true))); }
        catch (Exception failure) { throw new BadRequest(); }
        Receipt receipt = service.accept(scope, taskId, key, command);
        if ("REJECTED".equals(receipt.state())) return response(status(receipt.errorCode()), JsonResult.failure(receipt.errorCode(), "Delivery review rejected"));
        return response("COMMITTED".equals(receipt.state()) ? HttpStatus.OK : HttpStatus.ACCEPTED, JsonResult.success(receipt));
    }
    @GetMapping(value = "/request", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<Receipt>> byKey(@PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request, Authentication authentication) {
        noQuery(request); return response(HttpStatus.OK, JsonResult.success(service.byKey(scope(authentication), taskId, key)));
    }
    @GetMapping(value = "/{operationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<Receipt>> byOperation(@PathVariable String taskId, @PathVariable String operationId,
            HttpServletRequest request, Authentication authentication) {
        noQuery(request); return response(HttpStatus.OK, JsonResult.success(service.byOperation(scope(authentication), taskId, operationId)));
    }
    @ExceptionHandler(BadRequest.class) public ResponseEntity<JsonResult<Void>> bad() { return error(HttpStatus.BAD_REQUEST, "DELIVERY_REVIEW_BAD_REQUEST"); }
    @ExceptionHandler(Missing.class) public ResponseEntity<JsonResult<Void>> missing() { return error(HttpStatus.NOT_FOUND, "DELIVERY_REVIEW_NOT_FOUND"); }
    @ExceptionHandler(Conflict.class) public ResponseEntity<JsonResult<Void>> conflict() { return error(HttpStatus.CONFLICT, "DELIVERY_REVIEW_CONFLICT"); }
    @ExceptionHandler(IdempotencyConflict.class) public ResponseEntity<JsonResult<Void>> keyConflict() { return error(HttpStatus.CONFLICT, "DELIVERY_REVIEW_IDEMPOTENCY_CONFLICT"); }
    @ExceptionHandler({DataAccessException.class, Storage.class}) public ResponseEntity<JsonResult<Void>> storage() { return error(HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_REVIEW_STORAGE_UNAVAILABLE"); }
    @ExceptionHandler(cn.jia.agent.exception.AgentTaskCollaborationException.class)
    public ResponseEntity<JsonResult<Void>> domain(cn.jia.agent.exception.AgentTaskCollaborationException failure) {
        return switch (failure.getReason()) {
            case FORBIDDEN, NOT_FOUND -> error(HttpStatus.NOT_FOUND, "DELIVERY_REVIEW_NOT_FOUND");
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, "DELIVERY_REVIEW_BAD_REQUEST");
            case VERSION_CONFLICT, INVALID_TRANSITION -> error(HttpStatus.CONFLICT, "DELIVERY_REVIEW_CONFLICT");
            default -> error(HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_REVIEW_STORAGE_UNAVAILABLE");
        };
    }
    private Scope scope(Authentication authentication) {
        String tenant = tenants.resolve(authentication);
        ServerResolvedSender sender = identities.resolve(EsContextHolder.getContext());
        if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())) throw new Missing();
        return new Scope(tenant, sender.clientId(), sender.jiacn());
    }
    private static void noQuery(HttpServletRequest request) { if (request.getQueryString() != null) throw new BadRequest(); }
    private static HttpStatus status(String code) {
        return switch (code) {
            case "DELIVERY_REVIEW_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "DELIVERY_REVIEW_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
    }
    private static ResponseEntity<JsonResult<Void>> error(HttpStatus status, String code) { return response(status, JsonResult.failure(code, "Delivery review is unavailable")); }
    private static <T> ResponseEntity<JsonResult<T>> response(HttpStatus status, JsonResult<T> body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(body);
    }
}
