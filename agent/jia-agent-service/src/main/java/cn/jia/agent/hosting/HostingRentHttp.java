package cn.jia.agent.hosting;

import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Strict money wire boundary: no fallback identities, numeric coercion or hidden query parameters. */
public final class HostingRentHttp {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private HostingRentHttp() { }

    public record Actor(String actorId, String tenantId, String clientId) {
        public Actor {
            exact(actorId, 100); exact(tenantId, 50); exact(clientId, 50);
        }
        public EconomyScope scope() { return new EconomyScope(tenantId, clientId); }
        public EconomyPrincipal principal() { return new EconomyPrincipal(EconomyPrincipalType.USER, actorId); }
    }

    public static Actor actor(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            throw new HostingRentApplicationException(401, "HOSTING_RENT_UNAUTHENTICATED");
        }
        try {
            Map<String, Object> claims = jwt.getToken().getClaims();
            return new Actor(claim(claims, "sub"), claim(claims, "jiacn"), claim(claims, "client_id"));
        } catch (IllegalArgumentException exception) {
            throw new HostingRentApplicationException(403, "HOSTING_RENT_FORBIDDEN");
        }
    }

    private static String claim(Map<String, Object> claims, String name) {
        if (!(claims.get(name) instanceof String text)) throw new IllegalArgumentException("missing claim");
        return text;
    }

    public static Map<String, String> body(byte[] bytes, Set<String> allowed, Set<String> nullable) {
        if (bytes == null || bytes.length == 0 || bytes.length > 4096) throw badRequest();
        try {
            Map<String, Object> node = JSON.readValue(bytes, new TypeReference<Map<String, Object>>() { });
            if (node == null) throw badRequest();
            Map<String, String> result = new TreeMap<>();
            for (String name : node.keySet()) {
                if (!allowed.contains(name)) throw badRequest();
                Object value = node.get(name);
                if (value == null && nullable.contains(name)) result.put(name, null);
                else if (value instanceof String text) result.put(name, text);
                else throw badRequest();
            }
            return Collections.unmodifiableMap(result);
        } catch (HostingRentApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest();
        }
    }

    public static String key(HttpServletRequest request) {
        noQuery(request);
        var headers = Collections.list(request.getHeaders("Idempotency-Key"));
        if (headers.size() != 1) throw badRequest();
        return key(headers.getFirst());
    }

    public static String key(String value) {
        try {
            if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value)) throw badRequest();
            return value;
        } catch (IllegalArgumentException exception) { throw badRequest(); }
    }

    public static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw badRequest();
    }

    public static String required(Map<String, String> body, String name) {
        String text = body.get(name);
        try { exact(text, 100); }
        catch (IllegalArgumentException invalid) { throw badRequest(); }
        return text;
    }

    public static long positive(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw badRequest();
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw badRequest(); }
    }

    public static void exact(String text, int maxBytes) {
        if (text == null || text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || padding(text.codePointAt(0)) || padding(text.codePointBefore(text.length()))
                || text.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid exact text");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("invalid UTF-8");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid UTF-8");
        }
    }

    private static boolean padding(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }

    public static byte[] hash(String operation, Map<String, String> body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(operation);
            for (var entry : new TreeMap<>(body).entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeBoolean(entry.getValue() != null);
                if (entry.getValue() != null) out.writeUTF(entry.getValue());
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (Exception exception) { throw new IllegalStateException("cannot encode hosting request", exception); }
    }

    public static HostingRentApplicationException badRequest() {
        return new HostingRentApplicationException(400, "BAD_REQUEST");
    }
}
