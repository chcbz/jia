package cn.jia.core.security;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataSanitizerTest {

    @Test
    void sanitizesSensitiveMapFieldsWithoutChangingSource() {
        Map<String, Object> source = new HashMap<>();
        source.put("username", "songjiang");
        source.put("password", "raw-password");
        source.put("clientSecret", "raw-secret");

        Object sanitized = SensitiveDataSanitizer.sanitize(source);

        assertInstanceOf(Map.class, sanitized);
        Map<?, ?> result = (Map<?, ?>) sanitized;
        assertEquals("songjiang", result.get("username"));
        assertNull(result.get("password"));
        assertNull(result.get("clientSecret"));
        assertEquals("raw-password", source.get("password"));
    }

    @Test
    void sanitizesNestedBeansAndLists() {
        Account account = new Account();
        account.name = "wu-yong";
        account.password = "raw-password";
        account.profile = new Profile();
        account.profile.apiKey = "raw-api-key";
        account.sessions = List.of(Map.of("refreshToken", "raw-refresh-token", "title", "hall"));

        Object sanitized = SensitiveDataSanitizer.sanitize(account);

        assertInstanceOf(Map.class, sanitized);
        Map<?, ?> result = (Map<?, ?>) sanitized;
        assertEquals("wu-yong", result.get("name"));
        assertNull(result.get("password"));
        assertInstanceOf(Map.class, result.get("profile"));
        assertNull(((Map<?, ?>) result.get("profile")).get("apiKey"));
        assertInstanceOf(List.class, result.get("sessions"));
        Map<?, ?> session = (Map<?, ?>) ((List<?>) result.get("sessions")).get(0);
        assertNull(session.get("refreshToken"));
        assertEquals("hall", session.get("title"));
        assertEquals("raw-password", account.password);
    }

    @Test
    void redactsSensitiveValuesEmbeddedInTextFields() {
        Map<String, Object> source = Map.of(
                "content", """
                        {"password":"json-password","spring.datasource.password":"property-password"}
                        callback?access_token=query-token&safe=ok
                        --jasypt.encryptor.password=cli-password
                        """);

        Map<?, ?> result = (Map<?, ?>) SensitiveDataSanitizer.sanitize(source);
        String content = (String) result.get("content");

        assertFalse(content.contains("json-password"));
        assertFalse(content.contains("property-password"));
        assertFalse(content.contains("query-token"));
        assertFalse(content.contains("cli-password"));
        assertTrue(content.contains("\"password\":\"******\""));
        assertTrue(content.contains("safe=ok"));
    }

    @Test
    void supportsCustomTextRedactionPatterns() {
        SensitiveSanitizeConfig config = SensitiveSanitizeConfig.defaults();
        config.setTextRedactionPatterns(List.of("(session_id=)([^&\\s]+)"));

        Map<?, ?> result = (Map<?, ?>) SensitiveDataSanitizer.sanitize(
                Map.of("content", "callback?session_id=session-secret&safe=ok"), config);
        String content = (String) result.get("content");

        assertFalse(content.contains("session-secret"));
        assertTrue(content.contains("session_id=******"));
        assertTrue(content.contains("safe=ok"));
    }

    @Test
    void masksDisplayFieldsOnlyWhenConfigured() {
        Map<String, Object> source = Map.of("phone", "13800138000", "email", "hero@example.com");

        SensitiveSanitizeConfig config = SensitiveSanitizeConfig.defaults();
        assertEquals("13800138000", ((Map<?, ?>) SensitiveDataSanitizer.sanitize(source, config)).get("phone"));

        config.setMaskDisplayFields(true);
        Map<?, ?> result = (Map<?, ?>) SensitiveDataSanitizer.sanitize(source, config);

        assertEquals("138****8000", result.get("phone"));
        assertEquals("h***@example.com", result.get("email"));
    }

    @Test
    void fieldAnnotationOverridesNameRules() {
        AnnotatedSecret source = new AnnotatedSecret();
        source.visibleToken = "registration-token";
        source.normal = "ok";

        Map<?, ?> result = (Map<?, ?>) SensitiveDataSanitizer.sanitize(source);

        assertEquals("registration-token", result.get("visibleToken"));
        assertEquals("ok", result.get("normal"));
    }

    @Test
    void respectsDisabledAndAuditOnlyModes() {
        Map<String, Object> source = Map.of("password", "raw-password");

        SensitiveSanitizeConfig disabled = SensitiveSanitizeConfig.defaults();
        disabled.setEnabled(false);
        assertSame(source, SensitiveDataSanitizer.sanitize(source, disabled));

        SensitiveSanitizeConfig auditOnly = SensitiveSanitizeConfig.defaults();
        auditOnly.setAuditOnly(true);
        assertEquals("raw-password", ((Map<?, ?>) SensitiveDataSanitizer.sanitize(source, auditOnly)).get("password"));
    }

    @Test
    void handlesCyclicReferences() {
        Node root = new Node();
        root.name = "root";
        root.password = "raw-password";
        root.next = root;

        Map<?, ?> result = (Map<?, ?>) SensitiveDataSanitizer.sanitize(root);

        assertEquals("root", result.get("name"));
        assertNull(result.get("password"));
        assertEquals("[Circular]", result.get("next"));
    }

    static class Account {
        String name;
        String password;
        Profile profile;
        List<Map<String, Object>> sessions;
    }

    static class Profile {
        String apiKey;
    }

    static class AnnotatedSecret {
        @SensitiveField(strategy = SensitiveStrategy.KEEP)
        String visibleToken;
        String normal;
    }

    static class Node {
        String name;
        String password;
        Node next;
    }
}
