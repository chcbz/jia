package cn.jia.agent.api;

import cn.jia.agent.service.HostingRentAdmissionException;
import cn.jia.agent.hosting.HostingRentApplicationService;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.Set;
import cn.jia.agent.service.HostingRentAdmissionService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Paid hosting HTTP boundary. Legacy R00 construction remains fail-closed for focused compatibility tests. */
@RestController
@RequestMapping("/agent")
public final class AgentHostingRentController {
    private static final String CACHE_CONTROL = "private, no-store";
    private static final int MAX_BODY_BYTES = 1024;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final HostingRentAdmissionService admissionService;
    private final HostingRentApplicationService application;

    @Autowired
    public AgentHostingRentController(HostingRentApplicationService application) {
        this.application = Objects.requireNonNull(application);
        this.admissionService = null;
    }

    public AgentHostingRentController(HostingRentAdmissionService admissionService) {
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.application = null;
    }

    @PostMapping(value = "/personas/{personaCode}/hosting-rent/quotes", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Object quote(
            @PathVariable String personaCode,
            Authentication authentication,
            HttpServletRequest request,
            @RequestBody(required = false) byte[] rawBody) {
        HostingRentAdmissionService.Principal principal = requirePrincipal(authentication);
        requireNoQuery(request);
        if (application == null) {
            requireEmptyObject(rawBody);
            admissionService.requireQuoteAvailable(principal, personaCode);
            return null;
        }
        return success(application.quote(HostingRentHttp.actor(authentication), personaCode,
                HostingRentHttp.key(request), HostingRentHttp.body(rawBody, Set.of("purpose", "agentId"), Set.of("agentId"))));
    }

    @GetMapping("/{agentId}/hosting-lease")
    public Object lease(@PathVariable String agentId, Authentication authentication, HttpServletRequest request) {
        HostingRentHttp.noQuery(request);
        return success(application.lookup(HostingRentHttp.actor(authentication), agentId));
    }

    @PostMapping(value = "/hosting-leases/{leaseId}/renewal-quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object renewalQuote(@PathVariable String leaseId, Authentication authentication,
            HttpServletRequest request, @RequestBody byte[] body) {
        return success(application.renewalQuote(HostingRentHttp.actor(authentication), leaseId,
                HostingRentHttp.key(request), HostingRentHttp.body(body,
                        Set.of("agentId", "expectedLeaseVersion"), Set.of())));
    }

    @PostMapping(value = "/hosting-leases/{leaseId}/renewals", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object renew(@PathVariable String leaseId, Authentication authentication,
            HttpServletRequest request, @RequestBody byte[] body) {
        return success(application.renew(HostingRentHttp.actor(authentication), leaseId,
                HostingRentHttp.key(request), HostingRentHttp.body(body, Set.of("agentId", "quoteId",
                        "expectedLeaseVersion", "expectedPlanVersion", "expectedAmountMicro", "expectedPeriodSeconds"), Set.of())));
    }

    @ExceptionHandler(RuntimeException.class)
    public Object rentFailure(RuntimeException failure) { return HostingRentErrors.response(failure); }

    private Object success(Object data) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(JsonResult.success(data));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<JsonResult<Void>> authenticationFailure(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "HOSTING_RENT_FORBIDDEN" : "HOSTING_RENT_UNAUTHENTICATED",
                "Hosting rent access is unavailable");
    }

    @ExceptionHandler({RequestFailure.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<JsonResult<Void>> badRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid hosting rent request");
    }

    @ExceptionHandler(HostingRentAdmissionException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(HostingRentAdmissionException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.code(), exception.getMessage());
    }

    private static HostingRentAdmissionService.Principal requirePrincipal(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        String actor = requiredClaim(claims, "sub", 100);
        String tenant = requiredClaim(claims, "jiacn", 50);
        String client = requiredClaim(claims, "client_id", 50);
        return new HostingRentAdmissionService.Principal(actor, tenant, client);
    }

    private static String requiredClaim(Map<String, Object> claims, String name, int maxBytes) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || !validExact(text, maxBytes)) {
            throw new AuthenticationFailure(true);
        }
        return text;
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new RequestFailure();
        }
    }

    private static void requireEmptyObject(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY_BYTES) {
            throw new RequestFailure();
        }
        try {
            JsonNode root = STRICT_JSON.readTree(rawBody);
            if (root == null || !root.isObject() || root.size() != 0) {
                throw new RequestFailure();
            }
        } catch (RequestFailure failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
    }

    private static boolean validExact(String value, int maxBytes) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))) {
            return false;
        }
        return value.codePoints().noneMatch(Character::isISOControl);
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

    private static ResponseEntity<JsonResult<Void>> error(HttpStatus status, String code, String message) {
        JsonResult<Void> result = JsonResult.failure(code, message);
        result.setStatus(status.value());
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(result);
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
