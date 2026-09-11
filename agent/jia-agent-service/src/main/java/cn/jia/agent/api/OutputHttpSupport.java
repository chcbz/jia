package cn.jia.agent.api;

import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.dto.OutputPublishDTO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class OutputHttpSupport {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> TASK_ALLOWED = Set.of("runId", "expectedPreviousVersion",
            "title", "artifactType", "content", "objectId", "artifactId", "artifactVersion",
            "workItemId", "visibility", "publishToOwner");
    private static final Set<String> TASK_REQUIRED = Set.of("runId", "expectedPreviousVersion",
            "title", "artifactType", "artifactId", "artifactVersion");
    private static final Set<String> CHAT_ALLOWED = Set.of("runId", "expectedPreviousVersion",
            "title", "artifactType", "content", "objectId", "outputId", "version");
    private static final Set<String> CHAT_REQUIRED = Set.of("runId", "expectedPreviousVersion",
            "title", "artifactType", "outputId", "version");

    public record Scope(String tenantId, String clientId, String jiacn) { }

    public static Scope scope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt))
            throw new OutputDeliveryException("OUTPUT_AUTH_UNAUTHORIZED",
                    "Output authentication is required", 401, false);
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object jiacn = claims.get("jiacn"), client = claims.get("client_id");
        if (!(jiacn instanceof String owner) || !(client instanceof String clientId)
                || !exact(owner, 50) || !exact(clientId, 50))
            throw new OutputDeliveryException("OUTPUT_AUTH_FORBIDDEN",
                    "Output access is unavailable", 403, false);
        return new Scope(owner, clientId, owner);
    }

    public static OutputPublishDTO taskPublish(byte[] body) {
        JsonNode n = parse(body, TASK_ALLOWED, TASK_REQUIRED);
        return new OutputPublishDTO(text(n,"runId"), text(n,"expectedPreviousVersion"),
                text(n,"title"), text(n,"artifactType"), optionalText(n,"content"),
                optionalText(n,"objectId"), text(n,"artifactId"), text(n,"artifactVersion"),
                optionalText(n,"workItemId"), optionalText(n,"visibility"),
                optionalBoolean(n,"publishToOwner"));
    }

    public static OutputPublishDTO chatPublish(byte[] body) {
        JsonNode n = parse(body, CHAT_ALLOWED, CHAT_REQUIRED);
        return new OutputPublishDTO(text(n,"runId"), text(n,"expectedPreviousVersion"),
                text(n,"title"), text(n,"artifactType"), optionalText(n,"content"),
                optionalText(n,"objectId"), text(n,"outputId"), text(n,"version"),
                null, null, null);
    }

    public static String optionalQuery(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw bad();
        return values[0];
    }

    public static Integer optionalLimit(HttpServletRequest request) {
        String value = optionalQuery(request, "limit");
        if (value == null) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException invalid) { throw bad(); }
    }

    private static JsonNode parse(byte[] body, Set<String> allowed, Set<String> required) {
        if (body == null || body.length == 0 || body.length > 300_000) throw bad();
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) throw bad();
            Set<String> fields = new HashSet<>(node.propertyNames());
            if (!allowed.containsAll(fields) || !fields.containsAll(required)) throw bad();
            return node;
        } catch (OutputDeliveryException invalid) { throw invalid; }
        catch (Exception invalid) { throw bad(); }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString()) throw bad();
        return value.asText();
    }
    private static String optionalText(JsonNode node, String name) {
        if (!node.has(name)) return null;
        return text(node, name);
    }
    private static Boolean optionalBoolean(JsonNode node, String name) {
        if (!node.has(name)) return null;
        JsonNode value = node.get(name); if (!value.isBoolean()) throw bad();
        return value.asBoolean();
    }
    private static boolean exact(String value, int max) {
        return value != null && !value.isEmpty() && value.length() <= max
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static OutputDeliveryException bad() {
        return new OutputDeliveryException("OUTPUT_REQUEST_INVALID", "Invalid output request", 400, false);
    }
    private OutputHttpSupport() { }
}
