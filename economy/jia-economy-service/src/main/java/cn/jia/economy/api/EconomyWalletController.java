package cn.jia.economy.api;

import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.MicroSilver;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Development-preview wallet edge. JWT claims are the only scope source. */
@RestController
@RequestMapping("/economy")
public final class EconomyWalletController {
    private static final String CACHE_CONTROL_VALUE = "private, no-store";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final EconomyPreviewGate gate;
    private final EconomyWalletService walletService;

    public EconomyWalletController(EconomyPreviewGate gate, EconomyWalletService walletService) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.walletService = Objects.requireNonNull(walletService, "walletService");
    }

    @GetMapping(value = "/wallet", produces = MediaType.APPLICATION_JSON_VALUE)
    public WalletResponse wallet(HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        Subject subject = requireSubject(authentication);
        requirePreviewScope(subject.scope());
        EconomyWalletService.WalletSnapshot snapshot = walletService.wallet(subject.scope(), subject.actorId());
        return new WalletResponse(EconomyConstants.CURRENCY_SILVER, MicroSilver.format(snapshot.availableMicro()),
                MicroSilver.format(snapshot.heldMicro()), Long.toString(snapshot.version()));
    }

    @GetMapping(value = "/ledger", produces = MediaType.APPLICATION_JSON_VALUE)
    public LedgerResponse ledger(HttpServletRequest request, Authentication authentication) {
        Subject subject = requireSubject(authentication);
        requirePreviewScope(subject.scope());
        Query query = parseLedgerQuery(request);
        EconomyWalletService.LedgerPage page = walletService.ledger(
                subject.scope(), subject.actorId(), query.cursor(), query.limit());
        List<LedgerItemResponse> items = new ArrayList<>(page.rows().size());
        for (EconomyWalletLedgerRow row : page.rows()) items.add(toLedgerItem(row));
        return new LedgerResponse(List.copyOf(items), encodeCursor(page.nextCursor()));
    }

    @PostMapping(value = "/preview/issuances", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IssuanceResponse issue(@RequestBody byte[] rawBody, HttpServletRequest request,
            Authentication authentication) {
        requireNoQuery(request);
        Subject subject = requireSubject(authentication);
        requirePreviewScope(subject.scope());
        if (!gate.testIssuanceEnabled()) throw disabled();
        String idempotencyKey = singleHeader(request, "Idempotency-Key");
        if (!CANONICAL_UUID.matcher(idempotencyKey).matches()) throw badRequest();
        IssuanceRequest body = parseIssuanceBody(rawBody);
        byte[] requestHash = requestHash(subject, body);
        EconomyPostingResult result = walletService.issue(subject.scope(), subject.actorId(), idempotencyKey,
                requestHash, body.amountMicro(), body.campaignRef());
        return new IssuanceResponse(result.transactionId(), result.status(), MicroSilver.format(body.amountMicro()));
    }

    @ExceptionHandler(EconomyRequestException.class)
    public ResponseEntity<EconomyError> requestFailure(EconomyRequestException exception) {
        return error(exception.status, exception.code, exception.getMessage(), false);
    }

    @ExceptionHandler(EconomyPostingException.class)
    public ResponseEntity<EconomyError> postingFailure(EconomyPostingException exception) {
        return switch (exception.reason()) {
            case FEATURE_DISABLED -> error(HttpStatus.FORBIDDEN, "ECONOMY_PREVIEW_DISABLED",
                    "Economy preview is disabled for this request", false);
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key is already bound to a different request", false);
            case INVALID_COMMAND, AMOUNT_RANGE_EXCEEDED, IMBALANCED_TRANSACTION, INSUFFICIENT_FUNDS,
                    ACCOUNT_NOT_FOUND, ACCOUNT_NOT_ACTIVE, ESCROW_CONFLICT -> error(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "Invalid economy request", false);
            case CONCURRENCY_CONFLICT -> error(HttpStatus.CONFLICT, "ECONOMY_CONCURRENCY_CONFLICT",
                    "Economy request conflicted; retry with the same Idempotency-Key", true);
            default -> error(HttpStatus.SERVICE_UNAVAILABLE, "ECONOMY_UNAVAILABLE",
                    "Economy service is temporarily unavailable", true);
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<EconomyError> unexpectedFailure(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ECONOMY_UNAVAILABLE",
                "Economy service is temporarily unavailable", true);
    }

    private void requirePreviewScope(EconomyScope scope) {
        if (!gate.allows(scope.tenantId(), scope.clientId())) throw disabled();
    }

    private static Subject requireSubject(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new EconomyRequestException(HttpStatus.UNAUTHORIZED, "ECONOMY_UNAUTHENTICATED",
                    "Authentication is required");
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object jiacn = claims.get("jiacn");
        Object clientId = claims.get("client_id");
        Object subject = claims.get("sub");
        if (!(jiacn instanceof String tenant) || !(clientId instanceof String client)
                || !(subject instanceof String actor)
                || !validExact(tenant, 50) || !validExact(client, 50) || !validExact(actor, 100)) {
            throw new EconomyRequestException(HttpStatus.FORBIDDEN, "ECONOMY_FORBIDDEN",
                    "Economy scope is unavailable");
        }
        return new Subject(new EconomyScope(tenant, client), actor);
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw badRequest();
    }

    private static Query parseLedgerQuery(HttpServletRequest request) {
        Set<String> allowed = Set.of("cursor", "limit");
        for (String name : request.getParameterMap().keySet()) {
            if (!allowed.contains(name) || request.getParameterValues(name) == null
                    || request.getParameterValues(name).length != 1) throw badRequest();
        }
        String[] cursorValues = request.getParameterValues("cursor");
        String cursorValue = cursorValues == null ? null : cursorValues[0];
        String[] limitValues = request.getParameterValues("limit");
        int limit = DEFAULT_LIMIT;
        if (limitValues != null) {
            String raw = limitValues[0];
            if (raw == null || !raw.matches("(?:[1-9]|[1-9][0-9]{1,2})")) throw badRequest();
            try {
                limit = Integer.parseInt(raw);
            } catch (NumberFormatException exception) {
                throw badRequest();
            }
            if (limit > MAX_LIMIT) throw badRequest();
        }
        return new Query(cursorValue == null || cursorValue.isEmpty() ? null : decodeCursor(cursorValue), limit);
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) throw badRequest();
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.contains(",")) throw badRequest();
        return value;
    }

    private static IssuanceRequest parseIssuanceBody(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) throw badRequest();
        try {
            JsonNode root = STRICT_JSON.readTree(rawBody);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.has("amountMicro") || !root.has("campaignRef")
                    || !root.get("amountMicro").isTextual() || !root.get("campaignRef").isTextual()) {
                throw badRequest();
            }
            String amount = root.get("amountMicro").textValue();
            String campaign = root.get("campaignRef").textValue();
            long parsed = MicroSilver.parseUnsigned(amount);
            if (parsed == 0 || !validExact(campaign, 100)) throw badRequest();
            return new IssuanceRequest(parsed, campaign);
        } catch (EconomyRequestException | EconomyPostingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest();
        }
    }

    private static byte[] requestHash(Subject subject, IssuanceRequest body) {
        try {
            var envelope = STRICT_JSON.createObjectNode();
            var actor = envelope.putObject("actor");
            actor.put("tenantId", subject.scope().tenantId());
            actor.put("clientId", subject.scope().clientId());
            actor.put("userId", subject.actorId());
            envelope.put("method", "POST");
            envelope.put("route", "/economy/preview/issuances");
            var closedBody = envelope.putObject("body");
            closedBody.put("amountMicro", MicroSilver.format(body.amountMicro()));
            closedBody.put("campaignRef", body.campaignRef());
            return MessageDigest.getInstance("SHA-256").digest(STRICT_JSON.writeValueAsBytes(envelope));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to canonicalize issuance request", exception);
        }
    }

    private static LedgerItemResponse toLedgerItem(EconomyWalletLedgerRow row) {
        if (row == null || !validExact(row.getTransactionId(), 100) || !validExact(row.getEntryId(), 140)
                || !validExact(row.getBusinessType(), 32) || !validExact(row.getBusinessRef(), 100)
                || !"POSTED".equals(row.getStatus()) || row.getPostedAt() == null || row.getPostedAt() < 0
                || row.getSignedAmountMicro() == null || row.getSignedAmountMicro() == 0) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "wallet ledger row is invalid");
        }
        long signed = row.getSignedAmountMicro();
        String direction = signed > 0 ? "CREDIT" : "DEBIT";
        long amount;
        try {
            amount = signed > 0 ? signed : Math.negateExact(signed);
        } catch (ArithmeticException exception) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "wallet ledger amount is invalid", exception);
        }
        return new LedgerItemResponse(row.getTransactionId(), row.getEntryId(), row.getBusinessType(),
                row.getBusinessRef(), direction, MicroSilver.format(amount), row.getStatus(),
                Long.toString(row.getPostedAt()));
    }

    private static String encodeCursor(EconomyWalletService.Cursor cursor) {
        if (cursor == null) return null;
        if (cursor.postedAt() < 0 || cursor.rowId() < 1) {
            throw new EconomyPostingException(EconomyPostingException.Reason.JOURNAL_CORRUPT,
                    "wallet ledger cursor is invalid");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteBuffer.allocate(16).putLong(cursor.postedAt()).putLong(cursor.rowId()).array());
    }

    private static EconomyWalletService.Cursor decodeCursor(String value) {
        if (!value.matches("[A-Za-z0-9_-]{22}")) throw badRequest();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != 16 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
                throw badRequest();
            }
            ByteBuffer bytes = ByteBuffer.wrap(decoded);
            long postedAt = bytes.getLong();
            long rowId = bytes.getLong();
            if (postedAt < 0 || rowId < 1) throw badRequest();
            return new EconomyWalletService.Cursor(postedAt, rowId);
        } catch (IllegalArgumentException exception) {
            throw badRequest();
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
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static EconomyRequestException badRequest() {
        return new EconomyRequestException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid economy request");
    }

    private static EconomyRequestException disabled() {
        return new EconomyRequestException(HttpStatus.FORBIDDEN, "ECONOMY_PREVIEW_DISABLED",
                "Economy preview is disabled for this request");
    }

    private static ResponseEntity<EconomyError> error(
            HttpStatus status, String code, String message, boolean retryable) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new EconomyError(code, message, retryable, null));
    }

    private record Subject(EconomyScope scope, String actorId) {
    }

    private record Query(EconomyWalletService.Cursor cursor, int limit) {
    }

    private record IssuanceRequest(long amountMicro, String campaignRef) {
    }

    public record WalletResponse(String currency, String availableMicro, String heldMicro, String version) {
    }

    public record LedgerResponse(List<LedgerItemResponse> items, String nextCursor) {
    }

    public record LedgerItemResponse(String transactionId, String entryId, String businessType, String businessRef,
                                     String direction, String amountMicro, String status, String postedAt) {
    }

    public record IssuanceResponse(String transactionId, String status, String amountMicro) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EconomyError(String code, String message, boolean retryable, String currentVersion) {
    }

    private static final class EconomyRequestException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        private EconomyRequestException(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }
}
