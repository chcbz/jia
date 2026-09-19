package cn.jia.agent.conversation;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Browser-safe W03 projection of authoritative execution/output references for one conversation. */
@Slf4j
@RestController
@RequestMapping("/agent/conversations")
public class ConversationDeliverableController {
    static final String CACHE_CONTROL = "private, no-store";
    static final int DEFAULT_LIMIT = 100;
    private static final Set<String> QUERY_FIELDS = Set.of("limit");

    private final ConversationDeliverableReadAdapter deliverables;

    public ConversationDeliverableController(ConversationDeliverableReadAdapter deliverables) {
        this.deliverables = Objects.requireNonNull(deliverables, "deliverables");
    }

    @GetMapping(value = "/{conversationId}/deliverables", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConversationDeliverableReadAdapter.Page> list(
            @PathVariable String conversationId,
            HttpServletRequest request,
            Authentication authentication) {
        ConversationDeliverableReadAdapter.Scope scope = requireJwtScope(authentication);
        int limit = requireLimit(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(deliverables.list(scope, conversationId, limit));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "CONVERSATION_DELIVERABLE_FORBIDDEN"
                        : "CONVERSATION_DELIVERABLE_UNAUTHENTICATED");
    }

    @ExceptionHandler(ConversationDeliverableReadAdapter.Failure.class)
    public ResponseEntity<ErrorBody> deliverableFailure(
            ConversationDeliverableReadAdapter.Failure failure) {
        return switch (failure.reason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "CONVERSATION_DELIVERABLE_NOT_FOUND");
            case UNAVAILABLE, CORRUPT_STATE -> unavailable();
        };
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(RequestFailure ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) {
        log.error("Conversation deliverable read failed: uri={}", request.getRequestURI());
        return unavailable();
    }

    private static ConversationDeliverableReadAdapter.Scope requireJwtScope(
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        String ownerJiacn = requiredClaim(claims, "jiacn", 50);
        String clientId = requiredClaim(claims, "client_id", 50);
        String subject = requiredClaim(claims, "sub", 100);
        if ("0".equals(ownerJiacn) || "0".equals(clientId)
                || !subject.equals(authentication.getName())) {
            throw new AuthenticationFailure(true);
        }
        return new ConversationDeliverableReadAdapter.Scope("0", clientId, ownerJiacn);
    }

    private static String requiredClaim(
            Map<String, Object> claims, String name, int maximumLength) {
        Object raw = claims.get(name);
        if (!(raw instanceof String value) || !exact(value, maximumLength)) {
            throw new AuthenticationFailure(true);
        }
        return value;
    }

    private static int requireLimit(HttpServletRequest request) {
        if (!QUERY_FIELDS.containsAll(request.getParameterMap().keySet())) {
            throw new RequestFailure();
        }
        String[] values = request.getParameterValues("limit");
        if (values == null) return DEFAULT_LIMIT;
        if (values.length != 1 || !values[0].matches("[1-9][0-9]{0,2}")) {
            throw new RequestFailure();
        }
        try {
            int limit = Integer.parseInt(values[0]);
            if (limit > 100 || !Integer.toString(limit).equals(values[0])) {
                throw new RequestFailure();
            }
            return limit;
        } catch (NumberFormatException invalid) {
            throw new RequestFailure();
        }
    }

    private static boolean exact(String value, int maximumLength) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maximumLength
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

    private static ResponseEntity<ErrorBody> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_DELIVERABLE_UNAVAILABLE");
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, "Conversation deliverables are unavailable"));
    }

    public record ErrorBody(String code, String message) { }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }

    private static final class RequestFailure extends RuntimeException { }
}
