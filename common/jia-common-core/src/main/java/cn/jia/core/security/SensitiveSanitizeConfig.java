package cn.jia.core.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SensitiveSanitizeConfig {
    private static final String DEFAULT_TEXT_REDACTION_PATTERN =
            "([\"']?[A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|credential|apikey|api_key|privatekey|private_key)[A-Za-z0-9_.-]*[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s&,;)}\\]]+)([\"']?)";

    private boolean enabled = true;
    private boolean auditOnly = false;
    private boolean maskDisplayFields = false;
    private boolean failOpen = true;
    private int maxDepth = 8;
    private SensitiveStrategy secretStrategy = SensitiveStrategy.NULL;
    private Set<String> secretFields = new LinkedHashSet<>();
    private Set<String> maskFields = new LinkedHashSet<>();
    private List<String> textRedactionPatterns = List.of();

    public static SensitiveSanitizeConfig defaults() {
        SensitiveSanitizeConfig config = new SensitiveSanitizeConfig();
        config.setSecretFields(Set.of(
                "password", "passwd", "pwd", "secret", "clientSecret", "apiKey",
                "privateKey", "accessKey", "mchKey", "keyContent", "authorization",
                "credential", "tokenHash", "accessToken", "refreshToken"
        ));
        config.setMaskFields(Set.of("phone", "mobile", "email", "idCard", "identityNo"));
        config.setTextRedactionPatterns(List.of(DEFAULT_TEXT_REDACTION_PATTERN));
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAuditOnly() {
        return auditOnly;
    }

    public void setAuditOnly(boolean auditOnly) {
        this.auditOnly = auditOnly;
    }

    public boolean isMaskDisplayFields() {
        return maskDisplayFields;
    }

    public void setMaskDisplayFields(boolean maskDisplayFields) {
        this.maskDisplayFields = maskDisplayFields;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public SensitiveStrategy getSecretStrategy() {
        return secretStrategy;
    }

    public void setSecretStrategy(SensitiveStrategy secretStrategy) {
        this.secretStrategy = secretStrategy;
    }

    public Set<String> getSecretFields() {
        return secretFields;
    }

    public void setSecretFields(Set<String> secretFields) {
        this.secretFields = normalize(secretFields);
    }

    public Set<String> getMaskFields() {
        return maskFields;
    }

    public void setMaskFields(Set<String> maskFields) {
        this.maskFields = normalize(maskFields);
    }

    public List<String> getTextRedactionPatterns() {
        return textRedactionPatterns;
    }

    public void setTextRedactionPatterns(List<String> textRedactionPatterns) {
        this.textRedactionPatterns = textRedactionPatterns == null ? List.of() : List.copyOf(textRedactionPatterns);
    }

    boolean isSecretField(String fieldName) {
        return secretFields.contains(normalize(fieldName));
    }

    boolean isMaskField(String fieldName) {
        return maskFields.contains(normalize(fieldName));
    }

    private static Set<String> normalize(Set<String> fields) {
        Set<String> normalized = new LinkedHashSet<>();
        if (fields == null) {
            return normalized;
        }
        for (String field : fields) {
            if (field != null && !field.isBlank()) {
                normalized.add(normalize(field));
            }
        }
        return normalized;
    }

    private static String normalize(String field) {
        if (field == null) {
            return "";
        }
        return field.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }
}
