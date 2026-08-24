package cn.jia.user.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AccountSecuritySqlContractTest {
    @Test
    void mapperCarriesExactLookupAndSingleStatementBoundedCas() throws IOException {
        String mapper = resource("/cn/jia/user/mapper/InfoMapper.xml");
        assertTrue(mapper.contains("OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{jiacn,jdbcType=VARCHAR})"));
        assertTrue(mapper.contains("CAST(jiacn AS BINARY(128)) = CAST(#{jiacn,jdbcType=VARCHAR} AS BINARY(128))"));
        assertTrue(mapper.contains("SET auth_epoch = auth_epoch + 1"));
        assertTrue(mapper.contains("account_state = _ascii'ACTIVE' COLLATE ascii_bin"));
        assertTrue(mapper.contains("auth_epoch &lt; 9223372036854775807"));
        assertFalse(mapper.toLowerCase().contains("select auth_epoch\n        from user_info\n        where id = #{userid"));
    }

    @Test
    void migrationIsAdditiveRepeatSafeAndRollbackKeepsColumns() throws IOException {
        String upgrade = resource("/db/account-security-foundation-upgrade.sql");
        assertTrue(upgrade.contains("information_schema.columns"));
        assertTrue(upgrade.contains("CHARACTER SET ascii COLLATE ascii_bin"));
        assertTrue(upgrade.contains("BIGINT NOT NULL DEFAULT 0"));
        assertFalse(upgrade.toUpperCase().contains("DROP COLUMN"));

        String rollback = resource("/db/account-security-foundation-rollback-prerequisite.sql");
        assertTrue(rollback.contains("rotate/replace the JWT signing key"));
        assertTrue(rollback.contains("invalidate every authorization-server HttpSession"));
        assertFalse(rollback.toUpperCase().contains("DROP COLUMN"));

        String preflight = resource("/db/account-security-foundation-preflight.sql");
        assertTrue(preflight.contains("SHOW CREATE TABLE user_info"));
        assertTrue(preflight.contains("SHOW CREATE TABLE oauth_client"));
        assertTrue(preflight.contains("target_client_invalid_json_count"));
        assertTrue(preflight.contains("exact_oauth_client_rollback_sql"));
        assertTrue(preflight.contains("HEX(client_secret)"));
        assertTrue(preflight.contains("client_secret_expires_at_hex"));

        String oauthClient = resource("/db/account-security-foundation-oauth-client.sql");
        assertTrue(oauthClient.contains("JSON_VALID(client_settings)"));
        assertTrue(oauthClient.contains("SELECT COUNT(*) AS target_count"));
        assertTrue(rollback.contains("DELETE FROM oauth_authorization"));
        assertFalse(rollback.contains("DELETE FROM oauth2_authorization"));
    }

    private static String resource(String path) throws IOException {
        try (var input = AccountSecuritySqlContractTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
