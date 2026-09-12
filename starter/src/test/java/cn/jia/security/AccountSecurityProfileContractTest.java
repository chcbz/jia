package cn.jia.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AccountSecurityProfileContractTest {
    private static final String CORS_ORIGIN =
            "cors.allowed.origin.patterns=https://kit.chaoyoufan.cn,https://api.chaoyoufan.cn";

    @Test
    void productionProfilesUseCredentialSafeCorsProperty() throws IOException {
        for (String profile : new String[]{"prod", "grey"}) {
            String properties = resource("/application-" + profile + ".properties");
            assertTrue(properties.contains(CORS_ORIGIN), profile);
            assertFalse(properties.contains("cors.allowed.origins="), profile);
            assertFalse(properties.contains("cors.allowed.origin.patterns=*"), profile);
        }
    }

    @Test
    void starterFixtureProvidesAccountSecurityDefaults() throws IOException {
        String schema = resource("/db/schema.sql");
        assertTrue(schema.contains("account_state varchar(16) NOT NULL DEFAULT 'ACTIVE'"));
        assertTrue(schema.contains("auth_epoch bigint NOT NULL DEFAULT 0"));
    }

    private static String resource(String path) throws IOException {
        try (var input = AccountSecurityProfileContractTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
