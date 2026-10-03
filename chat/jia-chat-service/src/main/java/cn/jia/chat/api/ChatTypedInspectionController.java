package cn.jia.chat.api;

import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatDeliberationService;
import cn.jia.chat.service.ChatTypedInspectionAdmissionService;
import cn.jia.chat.service.ChatTypedInspectionService;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix = "chat.typed-inspection", name = "enabled", havingValue = "true")
public final class ChatTypedInspectionController {
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private final ChatTypedInspectionAdmissionService admissions;
    private final ChatTypedInspectionService inspection;
    private final ChatDeliberationService deliberation;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatTypedInspectionController(ChatTypedInspectionAdmissionService admissions,
            ChatTypedInspectionService inspection, ChatDeliberationService deliberation,
            HumanSenderIdentityResolver identities, TenantScopeResolver tenants) {
        this.admissions = Objects.requireNonNull(admissions);
        this.inspection = Objects.requireNonNull(inspection);
        this.deliberation = Objects.requireNonNull(deliberation);
        this.identities = Objects.requireNonNull(identities);
        this.tenants = Objects.requireNonNull(tenants);
    }

    @PostMapping("/{conversationId}/interactions/inspection")
    public ResponseEntity<JsonResult<ChatTypedInspectionWire.Accepted>> admit(
            @PathVariable String conversationId, @RequestBody(required = false) String raw,
            Authentication authentication, HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
        String key = singleHeader(request, IDEMPOTENCY_HEADER);
        Scope scope = scope(authentication);
        ChatTypedInspectionWire.Accepted value = admissions.admit(scope.tenantId(), scope.sender(),
                conversationId, key, ChatTypedInspectionWire.parse(raw));
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(JsonResult.success(value));
    }

    @GetMapping("/{conversationId}/interactions/inspection/request")
    public ResponseEntity<JsonResult<ChatTypedInspectionWire.Accepted>> recover(
            @PathVariable String conversationId, Authentication authentication,
            HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0) throw invalid();
        String key = singleHeader(request, IDEMPOTENCY_HEADER);
        Scope scope = scope(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(JsonResult.success(admissions.recover(
                        scope.tenantId(), scope.sender(), conversationId, key)));
    }

    @GetMapping("/{conversationId}/requests/{requestId}/inspection-outcome")
    public ResponseEntity<JsonResult<Object>> outcome(
            @PathVariable String conversationId, @PathVariable String requestId,
            Authentication authentication, HttpServletRequest servletRequest) {
        if (servletRequest.getQueryString() != null || servletRequest.getContentLengthLong() > 0) {
            throw invalid();
        }
        Scope identity = scope(authentication);
        ChatDeliberationService.RequestView request = deliberation.getRequest(identity.tenantId(),
                identity.sender().jiacn(), identity.sender().clientId(), requestId);
        if (!conversationId.equals(request.conversationId()) || request.turns().size() != 1) {
            throw unavailable();
        }
        ChatTypedDeliberationStore.Scope storeScope = new ChatTypedDeliberationStore.Scope(
                identity.tenantId(), identity.sender().jiacn(), identity.sender().clientId(),
                conversationId, decimal(request.conversationGeneration()));
        Object value = inspection.read(storeScope, requestId,
                request.turns().getFirst().turnId(), decimal(request.requestRevision()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(JsonResult.success(value));
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> error(ChatDeliberationException failure) {
        HttpStatus status = switch (failure.reason()) {
            case INVALID_REQUEST -> failure.getMessage() != null
                    && (failure.getMessage().contains("source")
                            || failure.getMessage().contains("unsupported"))
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
            case INVALID_ROUTE, GAP -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PERSISTENCE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        JsonResult<Void> result = JsonResult.failure("TYPED_INSPECTION_" + failure.reason(),
                status == HttpStatus.NOT_FOUND
                        ? "Typed inspection is unavailable" : failure.getMessage());
        result.setStatus(status.value());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> runtime(RuntimeException failure) {
        JsonResult<Void> result = JsonResult.failure("TYPED_INSPECTION_UNAVAILABLE",
                "Typed inspection is temporarily unavailable");
        result.setStatus(503);
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    private Scope scope(Authentication authentication) {
        try {
            String tenant = tenants.resolve(authentication);
            ServerResolvedSender sender = identities.resolve(EsContextHolder.getContext());
            if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())
                    || !exact(sender.jiacn(), 50) || !exact(sender.clientId(), 50)) {
                throw unavailable();
            }
            return new Scope(tenant, sender);
        } catch (ChatDeliberationException failure) {
            throw failure;
        } catch (RuntimeException denied) {
            throw unavailable();
        }
    }

    private record Scope(String tenantId, ServerResolvedSender sender) { }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || !exact(values.getFirst(), 100)) throw invalid();
        return values.getFirst();
    }

    private static long decimal(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static ChatDeliberationException invalid() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                "Typed inspection request is invalid");
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Typed inspection is unavailable");
    }
}
