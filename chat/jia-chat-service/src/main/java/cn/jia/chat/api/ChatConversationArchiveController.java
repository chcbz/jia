package cn.jia.chat.api;

import cn.jia.chat.archive.conversation.ChatConversationArchiveException;
import cn.jia.chat.archive.conversation.ChatConversationArchiveService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict browser contract for one persisted assetRef or server-resolved textSelection. */
@RestController
@RequestMapping("/chat/conversations")
@ConditionalOnProperty(prefix = "chat.conversation-archive", name = "enabled", havingValue = "true")
public final class ChatConversationArchiveController {
    private static final Set<String> ROOT_KEYS = Set.of("mode", "items");
    private static final Set<String> ASSET_ITEM_KEYS = Set.of("assetRef");
    private static final Set<String> TEXT_ITEM_KEYS = Set.of("textSelection");
    private static final Set<String> ASSET_KEYS = Set.of("assetId", "revision");
    private static final Set<String> TEXT_KEYS = Set.of(
            "messageId", "startCodePoint", "endCodePoint", "sha256");

    private final ChatConversationArchiveService archives;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;

    public ChatConversationArchiveController(ChatConversationArchiveService archives,
            HumanSenderIdentityResolver identities, TenantScopeResolver tenants) {
        this.archives = Objects.requireNonNull(archives);
        this.identities = Objects.requireNonNull(identities);
        this.tenants = Objects.requireNonNull(tenants);
    }

    @PostMapping("/{conversationId}/archive-operations")
    public ResponseEntity<JsonResult<ChatConversationArchiveService.Receipt>> archive(
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body, Authentication authentication) {
        Parsed parsed = parse(body);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~:/+\\-]{8,160}"))
            throw invalid();
        var receipt = archives.archive(scope(authentication), parsed.command(conversationId, idempotencyKey));
        HttpStatus status = Set.of("pending", "saving").contains(receipt.state())
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(JsonResult.success(receipt));
    }

    @GetMapping("/{conversationId}/archive-operations/{operationId}")
    public ResponseEntity<JsonResult<ChatConversationArchiveService.Receipt>> status(
            @PathVariable String conversationId, @PathVariable String operationId,
            Authentication authentication) {
        var receipt = archives.get(scope(authentication), conversationId, operationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(JsonResult.success(receipt));
    }

    private ChatConversationArchiveStore.Scope scope(Authentication authentication) {
        try {
            String tenant = tenants.resolve(authentication);
            ServerResolvedSender sender = identities.resolve(EsContextHolder.getContext());
            if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())
                    || !exact(sender.jiacn(), 50) || !exact(sender.clientId(), 50)) throw unavailable();
            return new ChatConversationArchiveStore.Scope(tenant, sender.jiacn(), sender.clientId());
        } catch (RuntimeException denied) {
            if (denied instanceof ChatConversationArchiveException archive) throw archive;
            throw unavailable();
        }
    }

    private static Parsed parse(Map<String, Object> body) {
        if (body == null || !ROOT_KEYS.equals(body.keySet()) || !"create".equals(body.get("mode"))
                || !(body.get("items") instanceof List<?> items) || items.size() != 1
                || !(items.getFirst() instanceof Map<?, ?> item)) {
            throw invalid();
        }
        if (keys(item, ASSET_ITEM_KEYS) && item.get("assetRef") instanceof Map<?, ?> asset
                && keys(asset, ASSET_KEYS) && asset.get("assetId") instanceof String assetId) {
            long revision = positiveLong(asset.get("revision"));
            if (!assetId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw invalid();
            return new Parsed(new ChatConversationArchiveService.AssetRef(assetId, revision), null);
        }
        if (keys(item, TEXT_ITEM_KEYS) && item.get("textSelection") instanceof Map<?, ?> text
                && keys(text, TEXT_KEYS) && text.get("messageId") instanceof String messageId
                && text.get("sha256") instanceof String sha256) {
            if (!messageId.matches("[1-9][0-9]{0,18}") || !sha256.matches("[0-9a-f]{64}"))
                throw invalid();
            try {
                if (Long.parseLong(messageId) < 1) throw invalid();
            } catch (NumberFormatException invalid) {
                throw invalid();
            }
            int start = nonNegativeInt(text.get("startCodePoint"));
            int end = nonNegativeInt(text.get("endCodePoint"));
            if (end <= start) throw invalid();
            return new Parsed(null, new ChatConversationArchiveService.TextSelection(
                    messageId, start, end, sha256));
        }
        throw invalid();
    }


    private static boolean keys(Map<?, ?> value, Set<String> expected) {
        return value.keySet().stream().allMatch(String.class::isInstance)
                && expected.equals(value.keySet());
    }

    private static long positiveLong(Object value) {
        try {
            if (value instanceof String text && text.matches("[1-9][0-9]*"))
                return Long.parseLong(text);
            if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger) {
                long parsed = new BigInteger(String.valueOf(value)).longValueExact();
                if (parsed > 0) return parsed;
            }
            if (value instanceof BigDecimal decimal && decimal.scale() <= 0) {
                long parsed = decimal.longValueExact();
                if (parsed > 0) return parsed;
            }
        } catch (ArithmeticException | NumberFormatException ignored) {
            // Invalid shape is collapsed below.
        }
        throw invalid();
    }


    private static int nonNegativeInt(Object value) {
        try {
            BigInteger parsed = null;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof BigInteger)
                parsed = new BigInteger(String.valueOf(value));
            else if (value instanceof BigDecimal decimal && decimal.scale() <= 0)
                parsed = decimal.toBigIntegerExact();
            if (parsed != null && parsed.signum() >= 0
                    && parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0)
                return parsed.intValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            // Invalid shape is collapsed below.
        }
        throw invalid();
    }

    private record Parsed(ChatConversationArchiveService.AssetRef assetRef,
            ChatConversationArchiveService.TextSelection textSelection) {
        ChatConversationArchiveService.Command command(String conversationId, String idempotencyKey) {
            return new ChatConversationArchiveService.Command(
                    conversationId, idempotencyKey, assetRef, textSelection);
        }
    }

    @ExceptionHandler(ChatConversationArchiveException.class)
    public ResponseEntity<JsonResult<Void>> error(ChatConversationArchiveException failure) {
        HttpStatus status = switch (failure.reason()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND_OR_FORBIDDEN -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
            case UNSUPPORTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TEMPORARILY_UNAVAILABLE, PERSISTENCE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = "CONVERSATION_ARCHIVE_" + failure.reason();
        String message = status == HttpStatus.NOT_FOUND
                ? "Conversation archive source is unavailable" : failure.getMessage();
        JsonResult<Void> result = JsonResult.failure(code, message);
        result.setStatus(status.value());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static ChatConversationArchiveException invalid() {
        return new ChatConversationArchiveException(
                ChatConversationArchiveException.Reason.INVALID_REQUEST,
                "Archive request must contain exactly one create assetRef or textSelection");
    }
    private static ChatConversationArchiveException unavailable() {
        return new ChatConversationArchiveException(
                ChatConversationArchiveException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Conversation asset is unavailable");
    }
}
