package cn.jia.agent.api;

import cn.jia.agent.preview.EconomyReadOnlyPreviewDtos.EstimateInput;
import cn.jia.agent.preview.EconomyReadOnlyPreviewDtos.ParsedTokens;
import cn.jia.agent.preview.EconomyReadOnlyPreviewDtos.Principal;
import cn.jia.agent.preview.EconomyReadOnlyPreviewException;
import cn.jia.agent.preview.EconomyReadOnlyPreviewService;
import cn.jia.core.entity.JsonResult;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict JWT-only HTTP edge for contract economy-readonly-v1. */
@RestController
@RequestMapping("/economy/preview")
public final class EconomyReadOnlyPreviewController {
    static final String CACHE_CONTROL = "private, no-store";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final JsonMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final EconomyReadOnlyPreviewService preview;

    public EconomyReadOnlyPreviewController(EconomyReadOnlyPreviewService preview) {
        this.preview = Objects.requireNonNull(preview, "preview");
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> capabilities(Authentication authentication,
            HttpServletRequest request) {
        Principal principal = principal(authentication);
        noQuery(request);
        return ok(preview.capabilities(principal));
    }

    @GetMapping(value = "/wallet", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> wallet(Authentication authentication,
            HttpServletRequest request) {
        Principal principal = principal(authentication);
        noQuery(request);
        return ok(preview.wallet(principal));
    }

    @GetMapping(value = "/ledger", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> ledger(Authentication authentication,
            HttpServletRequest request) {
        Principal principal = principal(authentication);
        Query query = ledgerQuery(request);
        return ok(preview.ledger(principal, query.cursor(), query.limit()));
    }

    @GetMapping(value = "/skill-products", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> products(Authentication authentication,
            HttpServletRequest request) {
        Principal principal = principal(authentication);
        PageQuery query = pageQuery(request);
        return ok(preview.products(principal, query.offset(), query.limit()));
    }

    @GetMapping(value = "/skill-products/{productId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> product(@PathVariable String productId,
            Authentication authentication, HttpServletRequest request) {
        Principal principal = principal(authentication);
        noQuery(request);
        exactRequestId(productId);
        return ok(preview.product(principal, productId));
    }

    @GetMapping(value = "/agents/{agentId}/skills", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> agentSkills(@PathVariable String agentId,
            Authentication authentication, HttpServletRequest request) {
        Principal principal = principal(authentication);
        noQuery(request);
        exactRequestId(agentId);
        return ok(preview.agentSkills(principal, agentId));
    }

    @GetMapping(value = "/hosting-plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> hostingPlan(Authentication authentication,
            HttpServletRequest request) {
        Principal principal = principal(authentication);
        noQuery(request);
        return ok(preview.hostingPlan(principal));
    }

    @GetMapping(value = "/agents/{agentId}/hosting-lease", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> hostingLease(@PathVariable String agentId,
            Authentication authentication, HttpServletRequest request) {
        Principal principal = principal(authentication);
        noQuery(request);
        exactRequestId(agentId);
        return ok(preview.hostingLease(principal, agentId));
    }

    @PostMapping(value = "/bounty-estimates", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResult<Object>> bountyEstimate(@RequestBody byte[] body,
            Authentication authentication, HttpServletRequest request) {
        Principal principal = principal(authentication);
        noQuery(request);
        return ok(preview.estimate(principal, estimate(body)));
    }

    @ExceptionHandler(EconomyReadOnlyPreviewException.class)
    public ResponseEntity<PreviewResult<Object>> previewFailure(EconomyReadOnlyPreviewException exception) {
        return failure(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<PreviewResult<Object>> unreadable(HttpMessageNotReadableException ignored) {
        return failure(HttpStatus.BAD_REQUEST, "PREVIEW_BAD_REQUEST", "Invalid preview request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PreviewResult<Object>> unexpected(Exception ignored) {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "PREVIEW_DATA_UNAVAILABLE",
                "Preview data is unavailable");
    }

    private static Principal principal(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            throw new EconomyReadOnlyPreviewException(HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "Authentication is required");
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object owner = claims.get("jiacn");
        Object client = claims.get("client_id");
        Object actor = claims.get("sub");
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !(actor instanceof String actorId) || !EconomyReadOnlyPreviewService.exact(ownerJiacn, 50)
                || !EconomyReadOnlyPreviewService.exact(clientId, 50)
                || !EconomyReadOnlyPreviewService.exact(actorId, 100)
                || !Objects.equals(authentication.getName(), actorId)
                || "0".equals(ownerJiacn) || "0".equals(clientId) || "0".equals(actorId)
                || "anonymousUser".equals(actorId)) {
            throw new EconomyReadOnlyPreviewException(HttpStatus.FORBIDDEN,
                    "PREVIEW_SCOPE_UNAVAILABLE", "Preview scope is unavailable");
        }
        // This deployment stores all Agent/economy rows under tenant 0. JWT jiacn remains the
        // byte-exact personal owner key; it is never substituted for tenant or actor.
        return new Principal("0", clientId, ownerJiacn, actorId);
    }

    private static Query ledgerQuery(HttpServletRequest request) {
        exactQueryNames(request, Set.of("cursor", "limit"));
        String cursor = singleQuery(request, "cursor");
        String limit = singleQuery(request, "limit");
        return new Query(cursor == null ? null : EconomyReadOnlyPreviewService.decodeCursor(cursor),
                limit == null ? DEFAULT_LIMIT : boundedPositive(limit));
    }

    private static PageQuery pageQuery(HttpServletRequest request) {
        exactQueryNames(request, Set.of("offset", "limit"));
        String offset = singleQuery(request, "offset");
        String limit = singleQuery(request, "limit");
        int parsedOffset = offset == null ? 0 : nonNegativeInt(offset);
        int parsedLimit = limit == null ? DEFAULT_LIMIT : boundedPositive(limit);
        try {
            Math.addExact(parsedOffset, parsedLimit);
        } catch (ArithmeticException exception) {
            throw badRequest();
        }
        return new PageQuery(parsedOffset, parsedLimit);
    }

    private static void exactQueryNames(HttpServletRequest request, Set<String> allowed) {
        for (String name : request.getParameterMap().keySet()) {
            String[] values = request.getParameterValues(name);
            if (!allowed.contains(name) || values == null || values.length != 1) throw badRequest();
        }
    }

    private static String singleQuery(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1 || values[0] == null || values[0].isEmpty()) throw badRequest();
        return values[0];
    }

    private static int boundedPositive(String value) {
        if (!value.matches("[1-9][0-9]{0,2}")) throw badRequest();
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > MAX_LIMIT) throw badRequest();
            return parsed;
        } catch (NumberFormatException exception) {
            throw badRequest();
        }
    }

    private static int nonNegativeInt(String value) {
        if (!value.matches("0|[1-9][0-9]{0,9}")) throw badRequest();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw badRequest(); }
    }

    private static EstimateInput estimate(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > 8192) throw badRequest();
        try {
            JsonNode root = STRICT_JSON.readTree(raw);
            if (root == null || !root.isObject() || root.size() != 4
                    || !root.has("grossBountyAmountMicro") || !root.has("minimumAcceptedPayoutMicro")
                    || !root.has("estimatedTokens") || !root.has("worstTokens")) throw badRequest();
            return new EstimateInput(decimal(root.get("grossBountyAmountMicro")),
                    decimal(root.get("minimumAcceptedPayoutMicro")),
                    tokens(root.get("estimatedTokens")), tokens(root.get("worstTokens")));
        } catch (EconomyReadOnlyPreviewException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest();
        }
    }

    private static ParsedTokens tokens(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != 4
                || !node.has("input") || !node.has("cachedInput")
                || !node.has("output") || !node.has("reasoning")) throw badRequest();
        return new ParsedTokens(decimal(node.get("input")), decimal(node.get("cachedInput")),
                decimal(node.get("output")), decimal(node.get("reasoning")));
    }

    private static long decimal(JsonNode node) {
        if (node == null || !node.isTextual()) throw badRequest();
        String value = node.textValue();
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw badRequest();
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw badRequest(); }
    }

    private static void exactRequestId(String value) {
        if (!EconomyReadOnlyPreviewService.exact(value, 100)) throw badRequest();
    }

    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw badRequest();
    }

    private static EconomyReadOnlyPreviewException badRequest() {
        return EconomyReadOnlyPreviewService.badRequest();
    }

    private static ResponseEntity<PreviewResult<Object>> ok(Object data) {
        PreviewResult<Object> body = new PreviewResult<>();
        body.setCode("E0");
        body.setMsg(null);
        body.setData(data);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(body);
    }

    private static ResponseEntity<PreviewResult<Object>> failure(HttpStatus status, String code, String message) {
        PreviewResult<Object> body = new PreviewResult<>();
        body.setCode(code);
        body.setMsg(message);
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(body);
    }

    /** JsonResult wire with the v1 contract's `message` name instead of the legacy `msg` name. */
    public static final class PreviewResult<T> extends JsonResult<T> {
        @Override
        @JsonProperty("message")
        public String getMsg() {
            return super.getMsg();
        }

        @Override
        @JsonIgnore
        public int getStatus() {
            return super.getStatus();
        }

        @Override
        @JsonIgnore
        public String getLocation() {
            return super.getLocation();
        }
    }

    private record Query(EconomyReadOnlyPreviewService.Cursor cursor, int limit) { }
    private record PageQuery(int offset, int limit) { }
}
