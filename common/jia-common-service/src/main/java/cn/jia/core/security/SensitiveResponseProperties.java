package cn.jia.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "jia.security.response-sanitizer")
public class SensitiveResponseProperties {
    private boolean enabled = true;
    private String mode = "null";
    private boolean auditEnabled = true;
    private boolean auditOnly = false;
    private boolean failOpen = true;
    private boolean maskDisplayFields = false;
    private int maxDepth = 8;
    private Set<String> dropFields = new LinkedHashSet<>(SensitiveSanitizeConfig.defaults().getSecretFields());
    private Set<String> maskFields = new LinkedHashSet<>(SensitiveSanitizeConfig.defaults().getMaskFields());
    private List<String> textRedactionPatterns = SensitiveSanitizeConfig.defaults().getTextRedactionPatterns();
    private Set<String> allowPaths = new LinkedHashSet<>();

    public SensitiveSanitizeConfig toSanitizeConfig() {
        SensitiveSanitizeConfig config = SensitiveSanitizeConfig.defaults();
        config.setEnabled(enabled);
        config.setAuditOnly(auditOnly);
        config.setFailOpen(failOpen);
        config.setMaskDisplayFields(maskDisplayFields);
        config.setMaxDepth(maxDepth);
        config.setSecretFields(dropFields);
        config.setMaskFields(maskFields);
        config.setTextRedactionPatterns(textRedactionPatterns);
        config.setSecretStrategy(parseMode(mode));
        return config;
    }

    private SensitiveStrategy parseMode(String mode) {
        if ("drop".equalsIgnoreCase(mode)) {
            return SensitiveStrategy.DROP;
        }
        if ("mask".equalsIgnoreCase(mode)) {
            return SensitiveStrategy.MASK;
        }
        return SensitiveStrategy.NULL;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }

    public boolean isAuditOnly() {
        return auditOnly;
    }

    public void setAuditOnly(boolean auditOnly) {
        this.auditOnly = auditOnly;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public boolean isMaskDisplayFields() {
        return maskDisplayFields;
    }

    public void setMaskDisplayFields(boolean maskDisplayFields) {
        this.maskDisplayFields = maskDisplayFields;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public Set<String> getDropFields() {
        return dropFields;
    }

    public void setDropFields(Set<String> dropFields) {
        this.dropFields = dropFields == null ? new LinkedHashSet<>() : new LinkedHashSet<>(dropFields);
    }

    public Set<String> getMaskFields() {
        return maskFields;
    }

    public void setMaskFields(Set<String> maskFields) {
        this.maskFields = maskFields == null ? new LinkedHashSet<>() : new LinkedHashSet<>(maskFields);
    }

    public List<String> getTextRedactionPatterns() {
        return textRedactionPatterns;
    }

    public void setTextRedactionPatterns(List<String> textRedactionPatterns) {
        this.textRedactionPatterns = textRedactionPatterns == null ? List.of() : List.copyOf(textRedactionPatterns);
    }

    public Set<String> getAllowPaths() {
        return allowPaths;
    }

    public void setAllowPaths(Set<String> allowPaths) {
        this.allowPaths = allowPaths == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowPaths);
    }
}
