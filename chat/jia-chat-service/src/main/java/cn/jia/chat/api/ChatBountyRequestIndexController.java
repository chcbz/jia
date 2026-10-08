package cn.jia.chat.api;

import cn.jia.chat.service.ChatBountyRequestIndexService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict read-only owner catalogue; it does not mint execution, editing or finalization authority. */
@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix = "chat.bounty-media", name = "enabled", havingValue = "true")
public class ChatBountyRequestIndexController {
    private static final Set<String> QUERY_KEYS = Set.of(
            "expectedGeneration", "after", "through", "pageSize");
    private static final String CACHE_CONTROL = "private, no-store";

    private final ChatBountyRequestIndexService index;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatBountyRequestIndexController(ChatBountyRequestIndexService index,
            HumanSenderIdentityResolver identities, TenantScopeResolver tenants) {
        this.index = Objects.requireNonNull(index);
        this.identities = Objects.requireNonNull(identities);
        this.tenants = Objects.requireNonNull(tenants);
    }

    @GetMapping("/{conversationId}/requests")
    public ResponseEntity<JsonResult<ChatBountyRequestIndexService.Page>> requests(
            @PathVariable String conversationId, HttpServletRequest request,
            Authentication authentication) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (!QUERY_KEYS.containsAll(parameters.keySet()) || parameters.entrySet().stream()
                .anyMatch(entry -> entry.getValue() == null || entry.getValue().length != 1)
                || hasBody(request)) throw invalid();
        String tenant = tenants.resolve(authentication);
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            throw notFound();
        }
        Object ownerClaim = jwt.getToken().getClaims().get("jiacn");
        Object clientClaim = jwt.getToken().getClaims().get("client_id");
        var context = EsContextHolder.getContext();
        ServerResolvedSender sender;
        try {
            sender = identities.resolve(context);
        } catch (IllegalStateException unavailable) {
            throw notFound();
        }
        if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())
                || !(ownerClaim instanceof String owner) || !(clientClaim instanceof String client)
                || !owner.equals(sender.jiacn()) || !client.equals(sender.clientId())
                || context == null || !tenant.equals(context.getTenantId())
                || !owner.equals(context.getJiacn()) || !client.equals(context.getClientId())) {
            throw notFound();
        }
        var query = new ChatBountyRequestIndexService.Query(value(parameters, "expectedGeneration"),
                value(parameters, "after"), value(parameters, "through"),
                value(parameters, "pageSize"));
        return response(HttpStatus.OK, JsonResult.success(index.read(tenant, sender.jiacn(),
                sender.clientId(), conversationId, query)));
    }

    private static String value(Map<String, String[]> values, String key) {
        String[] found = values.get(key);
        return found == null ? null : found[0];
    }

    private static boolean hasBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) return true;
        try { return request.getInputStream().read() != -1; }
        catch (IOException malformed) { throw invalid(); }
    }

    @ExceptionHandler(ChatBountyRequestIndexService.Failure.class)
    public ResponseEntity<JsonResult<Void>> failure(ChatBountyRequestIndexService.Failure failure) {
        HttpStatus status = switch (failure.reason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String message = status == HttpStatus.NOT_FOUND
                ? "Bounty request index is unavailable" : switch (failure.reason()) {
                    case INVALID_REQUEST -> "Bounty request index request is invalid";
                    case CONFLICT -> "Bounty request index context changed";
                    case UNAVAILABLE -> "Bounty request index is temporarily unavailable";
                    default -> "Bounty request index is unavailable";
                };
        JsonResult<Void> result = JsonResult.failure("BOUNTY_REQUEST_INDEX_" + failure.reason(), message);
        result.setStatus(status.value());
        return response(status, result);
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> identityFailure(ChatDeliberationException failure) {
        return failure(new ChatBountyRequestIndexService.Failure(
                failure.reason() == ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN
                        ? ChatBountyRequestIndexService.Reason.NOT_FOUND_OR_FORBIDDEN
                        : ChatBountyRequestIndexService.Reason.UNAVAILABLE, failure));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> unexpected(RuntimeException failure) {
        return failure(new ChatBountyRequestIndexService.Failure(
                ChatBountyRequestIndexService.Reason.UNAVAILABLE, failure));
    }

    private static <T> ResponseEntity<JsonResult<T>> response(HttpStatus status, JsonResult<T> body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
    }

    private static ChatBountyRequestIndexService.Failure invalid() {
        return new ChatBountyRequestIndexService.Failure(
                ChatBountyRequestIndexService.Reason.INVALID_REQUEST);
    }
    private static ChatBountyRequestIndexService.Failure notFound() {
        return new ChatBountyRequestIndexService.Failure(
                ChatBountyRequestIndexService.Reason.NOT_FOUND_OR_FORBIDDEN);
    }
}
