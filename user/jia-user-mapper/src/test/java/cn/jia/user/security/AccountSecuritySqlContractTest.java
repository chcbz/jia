package cn.jia.user.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AccountSecuritySqlContractTest {
    private static final String CLIENT_ID = "jiafewnnv58ec2379c";
    private static final String EXACT_CLIENT_LENGTH =
            "OCTET_LENGTH(client_id) = OCTET_LENGTH(@asf_oauth_client_id)";
    private static final String EXACT_CLIENT_HEX = "HEX(client_id) = HEX(@asf_oauth_client_id)";

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
    void oauthClientMigrationUsesByteExactTargetAndFailsClosed() throws IOException {
        String preflight = resource("/db/account-security-foundation-preflight.sql");
        String migration = resource("/db/account-security-foundation-oauth-client.sql");

        assertTrue(preflight.contains("SET @asf_oauth_client_id := _ascii'" + CLIENT_ID + "'"));
        assertTrue(migration.contains("SET @asf_oauth_client_id := _ascii'" + CLIENT_ID + "'"));
        assertTrue(count(preflight, EXACT_CLIENT_LENGTH) >= 5, preflight);
        assertTrue(count(preflight, EXACT_CLIENT_HEX) >= 5, preflight);
        assertTrue(count(migration, EXACT_CLIENT_LENGTH) >= 6, migration);
        assertTrue(count(migration, EXACT_CLIENT_HEX) >= 6, migration);
        assertFalse(preflight.contains("WHERE client_id = '" + CLIENT_ID + "'"));
        assertFalse(migration.contains("WHERE client_id = '" + CLIENT_ID + "'"));

        assertTrue(preflight.contains("target_client_collation_collision_count"));
        assertTrue(migration.contains("@asf_target_collision_count = 0"));
        assertTrue(preflight.contains("account_security_foundation_preflight_requires_one_byte_exact_oauth_client"));
        assertTrue(migration.contains("account_security_foundation_migration_requires_one_byte_exact_oauth_client"));
        assertTrue(migration.contains("account_security_foundation_oauth_client_postcondition_failed"));
        assertTrue(migration.contains("@asf_post_collision_count = 0"));
        assertTrue(migration.contains("HEX(client_authentication_methods) = HEX(_ascii'none')"));
        assertTrue(migration.contains("HEX(redirect_uris) = HEX(_ascii'https://kit.chaoyoufan.cn/oauth2/callback')"));
        assertTrue(migration.contains("HEX(scopes) = HEX(_ascii'openid')"));
        assertTrue(preflight.contains("PREPARE asf_guard_stmt"));
        assertTrue(migration.contains("PREPARE asf_guard_stmt"));

        assertTrue(preflight.contains("OCTET_LENGTH(id)="));
        assertTrue(preflight.contains("HEX(id)="));
        assertTrue(preflight.contains("OCTET_LENGTH(client_id)="));
        assertTrue(preflight.contains("HEX(client_id)="));
        assertTrue(preflight.contains("exact_oauth_client_rollback_sql"));
        assertTrue(preflight.contains("HEX(client_secret)"));
        assertTrue(preflight.contains("client_secret_expires_at_hex"));
    }

    @Test
    void byteExactContractRejectsCaseTailAndFoldedCollisions() {
        assertFalse(byteExact(CLIENT_ID, CLIENT_ID.toUpperCase()));
        assertFalse(byteExact(CLIENT_ID, CLIENT_ID + " "));
        assertFalse(byteExact(CLIENT_ID, "J" + CLIENT_ID.substring(1)));
        assertTrue(byteExact(CLIENT_ID, CLIENT_ID));
    }

    @Test
    void upgradeValidatesInitialBackfillWithoutResettingLegitimateRepeatValues() throws IOException {
        String upgrade = resource("/db/account-security-foundation-upgrade.sql");
        assertTrue(upgrade.contains("information_schema.columns"));
        assertTrue(upgrade.contains("CHARACTER SET ascii COLLATE ascii_bin"));
        assertTrue(upgrade.contains("BIGINT NOT NULL DEFAULT 0"));
        assertTrue(upgrade.contains("@asf_added_account_state"));
        assertTrue(upgrade.contains("@asf_added_auth_epoch"));
        assertTrue(upgrade.contains("invalid_account_state_initial_backfill"));
        assertTrue(upgrade.contains("invalid_auth_epoch_initial_backfill"));
        assertTrue(upgrade.contains("MAX(OCTET_LENGTH(column_default)=OCTET_LENGTH(_ascii'ACTIVE'))"));
        assertTrue(upgrade.contains("MAX(HEX(column_default)=HEX(_ascii'ACTIVE'))"));
        assertTrue(upgrade.contains("HEX(_ascii'ACTIVE')"));
        assertTrue(upgrade.contains("HEX(_ascii'SUSPENDED')"));
        assertTrue(upgrade.contains("HEX(_ascii'DISABLED')"));
        assertTrue(upgrade.contains("auth_epoch IS NULL OR auth_epoch < 0"));
        assertFalse(upgrade.contains("invalid_existing_account_rows"));
        assertFalse(upgrade.toUpperCase().contains("UPDATE USER_INFO"));
        assertFalse(upgrade.toUpperCase().contains("DROP COLUMN"));
        assertFalse(upgrade.contains("9223372036854775807"));
    }

    @Test
    void rollbackKeepsColumnsAndInvalidatesPersistedGrants() throws IOException {
        String rollback = resource("/db/account-security-foundation-rollback-prerequisite.sql");
        assertTrue(rollback.contains("rotate/replace the JWT signing key"));
        assertTrue(rollback.contains("invalidate every authorization-server HttpSession"));
        assertTrue(rollback.contains("DELETE FROM oauth_authorization"));
        assertFalse(rollback.contains("DELETE FROM oauth2_authorization"));
        assertFalse(rollback.toUpperCase().contains("DROP COLUMN"));
    }

    private static boolean byteExact(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.US_ASCII);
        byte[] rightBytes = right.getBytes(StandardCharsets.US_ASCII);
        return leftBytes.length == rightBytes.length
                && HexFormat.of().formatHex(leftBytes).equals(HexFormat.of().formatHex(rightBytes));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(needle, offset)) >= 0; offset += needle.length()) {
            count++;
        }
        return count;
    }

    private static String resource(String path) throws IOException {
        try (var input = AccountSecuritySqlContractTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
