package cn.jia.agent.config;

import java.util.Locale;

/** Local startup switch: validate reads catalogs but never installs or migrates schema. */
final class SchemaValidationMode {
    private SchemaValidationMode() { }

    static boolean isValidateOnly(String configured) {
        String mode = configured == null ? "install" : configured.strip().toLowerCase(Locale.ROOT);
        if (mode.isEmpty() || "install".equals(mode)) return false;
        if ("validate".equals(mode)) return true;
        throw new IllegalStateException("Unsupported cyf.schema.mode: " + configured);
    }

    static void requireMutationAllowed(boolean validateOnly, String missingSchema) {
        if (validateOnly) {
            throw new IllegalStateException(missingSchema + " is unavailable in validate-only mode");
        }
    }
}
