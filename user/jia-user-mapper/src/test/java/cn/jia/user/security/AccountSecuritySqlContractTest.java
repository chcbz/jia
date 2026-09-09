package cn.jia.user.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class AccountSecuritySqlContractTest {
    private static final String CLIENT_ID = "jiafewnnv58ec2379c";
    private static final String PREFLIGHT_EXACT_CLIENT_LENGTH =
            "OCTET_LENGTH(client_id) = OCTET_LENGTH(@asf_oauth_client_id)";
    private static final String PREFLIGHT_EXACT_CLIENT_HEX = "HEX(client_id) = HEX(@asf_oauth_client_id)";
    private static final String MIGRATION_EXACT_CLIENT_LENGTH =
            "OCTET_LENGTH(client_id) = OCTET_LENGTH(_ascii'" + CLIENT_ID + "')";
    private static final String MIGRATION_EXACT_CLIENT_HEX =
            "HEX(client_id) = HEX(_ascii'" + CLIENT_ID + "')";

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
        assertTrue(count(preflight, PREFLIGHT_EXACT_CLIENT_LENGTH) >= 5, preflight);
        assertTrue(count(preflight, PREFLIGHT_EXACT_CLIENT_HEX) >= 5, preflight);
        assertTrue(count(migration, MIGRATION_EXACT_CLIENT_LENGTH) >= 6, migration);
        assertTrue(count(migration, MIGRATION_EXACT_CLIENT_HEX) >= 6, migration);
        assertFalse(preflight.contains("WHERE client_id = '" + CLIENT_ID + "'"));
        assertFalse(migration.contains("WHERE client_id = '" + CLIENT_ID + "'"));

        assertTrue(preflight.contains("SELECT OCTET_LENGTH(jiacn) AS jiacn_octets, HEX(jiacn) AS jiacn_hex,"));
        assertTrue(preflight.contains("GROUP BY OCTET_LENGTH(jiacn), HEX(jiacn) HAVING COUNT(*) > 1"));
        assertFalse(preflight.contains("GROUP BY CAST(jiacn AS BINARY)"));
        assertTrue(preflight.contains("SELECT COUNT(*) AS null_jiacn_count FROM user_info WHERE jiacn IS NULL"));
        assertTrue(preflight.contains("target_client_collation_collision_count"));
        assertTrue(preflight.contains("account_security_foundation_preflight_requires_one_byte_exact_oauth_client"));
        assertTrue(preflight.contains("PREPARE asf_guard_stmt"));

        assertTrue(migration.contains("DELIMITER $$"));
        assertTrue(migration.contains("CREATE PROCEDURE asf_migrate_account_security_oauth_client()"));
        assertTrue(migration.contains("SQL SECURITY INVOKER"));
        assertTrue(migration.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(migration.contains("ROLLBACK;"));
        assertTrue(migration.contains("RESIGNAL;"));
        assertTrue(migration.contains("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"));
        assertTrue(migration.contains("START TRANSACTION;"));
        assertTrue(migration.contains("FROM oauth_client FORCE INDEX (PRIMARY)"));
        assertTrue(migration.contains("WHERE id >= _utf8mb4''"));
        assertTrue(migration.contains("FOR UPDATE;"));
        assertTrue(migration.contains("engine = 'InnoDB'"));
        assertTrue(count(migration, "SIGNAL SQLSTATE '45000'") >= 5, migration);

        String update = migration.substring(
                migration.indexOf("UPDATE oauth_client"),
                migration.indexOf("SET v_updated_rows = ROW_COUNT()"));
        assertTrue(update.contains("AND v_exact_target_count = 1"));
        assertTrue(update.contains("AND v_target_collision_count = 0"));
        assertTrue(update.contains("AND v_target_invalid_json_count = 0"));
        assertTrue(update.contains("AND JSON_VALID(client_settings)"));
        assertTrue(update.contains("AND JSON_VALID(token_settings)"));
        assertTrue(migration.contains("IF v_updated_rows NOT IN (0, 1)"));
        assertFalse(migration.contains("IF v_updated_rows <> 1"));
        assertTrue(migration.contains("v_post_exact_target_count <> 1"));
        assertTrue(migration.contains("v_post_collision_count <> 0"));
        assertTrue(migration.contains("v_post_invalid_json_count <> 0"));
        assertTrue(migration.contains("v_postcondition_count <> 1"));
        assertTrue(migration.indexOf("IF v_updated_rows NOT IN (0, 1)")
                < migration.indexOf("v_postcondition_count <> 1"));
        assertTrue(migration.indexOf("v_postcondition_count <> 1") < migration.indexOf("COMMIT;"));
        assertTrue(migration.contains("COMMIT;"));
        assertTrue(migration.indexOf("COMMIT;") < migration.indexOf("CALL asf_migrate_account_security_oauth_client();"));
        assertTrue(migration.contains("CALL asf_migrate_account_security_oauth_client();\n"
                + "DROP PROCEDURE asf_migrate_account_security_oauth_client;"));
        assertFalse(migration.contains("PREPARE asf_guard_stmt"));
        assertFalse(migration.contains("account_security_foundation_migration_requires_one_byte_exact_oauth_client"));
        assertFalse(migration.contains("account_security_foundation_oauth_client_postcondition_failed"));

        assertTrue(migration.contains("HEX(client_authentication_methods) = HEX(_ascii'none')"));
        assertTrue(migration.contains("HEX(redirect_uris) = HEX(_ascii'https://kit.chaoyoufan.cn/oauth2/callback')"));
        assertTrue(migration.contains("HEX(scopes) = HEX(_ascii'openid')"));

        assertTrue(preflight.contains("OCTET_LENGTH(id)="));
        assertTrue(preflight.contains("HEX(id)="));
        assertTrue(preflight.contains("OCTET_LENGTH(client_id)="));
        assertTrue(preflight.contains("HEX(client_id)="));
        assertTrue(preflight.contains("exact_oauth_client_rollback_sql"));
        assertTrue(preflight.contains("HEX(client_secret)"));
        assertTrue(preflight.contains("client_secret_expires_at_hex"));
    }

    @Test
    void oauthClientHardeningKeepsPkceGrantAndCodeTtlWhileUsingTwentyFourHourAccessTokens() throws IOException {
        String migration = resource("/db/account-security-foundation-oauth-client.sql");
        String update = updateStatement(migration);

        assertTrue(update.contains("client_authentication_methods = 'none'"));
        assertTrue(update.contains("authorization_grant_types = 'authorization_code'"));
        assertTrue(update.contains("$.\"settings.client.require-proof-key\"', CAST('true' AS JSON)"));
        assertTrue(update.contains("$.\"settings.token.access-token-time-to-live\"', 'PT24H'"));
        assertTrue(update.contains("$.\"settings.token.authorization-code-time-to-live\"', 'PT5M'"));
        assertTrue(migration.contains("HEX(_ascii'PT24H')"));
        assertTrue(migration.contains("HEX(_ascii'PT5M')"));
        assertFalse(migration.contains("PT10M"));
    }

    @Test
    void ttlOnlyMigrationIsIdempotentByteExactAndChangesNoOtherClientField() throws IOException {
        String migration = resource("/db/account-security-access-token-ttl-24h.sql");

        assertTrue(migration.contains("Manual TTL-only migration; this file is not an automatic deployment step."));
        assertTrue(migration.contains("Before execution, capture and protect a byte-exact backup"));
        assertTrue(migration.contains("access tokens already issued keep their stored expiration"));
        assertTrue(migration.contains("CREATE PROCEDURE asf_migrate_account_security_access_token_ttl_24h()"));
        assertTrue(migration.contains("SQL SECURITY INVOKER"));
        assertTrue(migration.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(migration.contains("ROLLBACK;"));
        assertTrue(migration.contains("RESIGNAL;"));
        assertTrue(migration.contains("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE"));
        assertTrue(migration.contains("START TRANSACTION;"));
        assertTrue(migration.contains("FROM oauth_client FORCE INDEX (PRIMARY)"));
        assertTrue(migration.contains("WHERE id >= _utf8mb4''"));
        assertTrue(migration.contains("FOR UPDATE;"));
        assertTrue(migration.contains("engine = 'InnoDB'"));
        assertTrue(count(migration, MIGRATION_EXACT_CLIENT_LENGTH) >= 6, migration);
        assertTrue(count(migration, MIGRATION_EXACT_CLIENT_HEX) >= 6, migration);
        assertTrue(migration.contains("v_target_collision_count <> 0"));
        assertTrue(migration.contains("token_settings IS NULL OR NOT JSON_VALID(token_settings)"));
        assertTrue(count(migration, "SIGNAL SQLSTATE '45000'") >= 6, migration);

        String update = updateStatement(migration);
        String setClause = update.substring(update.indexOf("SET "), update.indexOf("\n     WHERE"))
                .replaceAll("\\s+", " ")
                .trim();
        assertEquals("SET token_settings = JSON_SET(token_settings, "
                + "'$.\"settings.token.access-token-time-to-live\"', 'PT24H')", setClause);
        assertEquals(1, count(migration, "UPDATE oauth_client"), migration);
        assertFalse(migration.contains("settings.token.authorization-code-time-to-live"));
        assertFalse(update.contains("refresh_token"));

        assertTrue(migration.contains("v_expected_token_settings"));
        assertTrue(migration.contains("OCTET_LENGTH(token_settings) = OCTET_LENGTH(v_expected_token_settings)"));
        assertTrue(migration.contains("HEX(token_settings) = HEX(v_expected_token_settings)"));
        assertTrue(migration.contains("IF v_updated_rows NOT IN (0, 1)"));
        assertFalse(migration.contains("IF v_updated_rows <> 1"));
        assertTrue(migration.indexOf("IF v_updated_rows NOT IN (0, 1)")
                < migration.indexOf("v_postcondition_count <> 1"));
        assertTrue(migration.indexOf("v_postcondition_count <> 1") < migration.indexOf("COMMIT;"));
        assertTrue(migration.contains("CALL asf_migrate_account_security_access_token_ttl_24h();\n"
                + "DROP PROCEDURE asf_migrate_account_security_access_token_ttl_24h;"));
        assertFalse(migration.contains("PT10M"));
        assertFalse(migration.contains("expiresIn"));
        assertFalse(migration.toLowerCase().contains("bff"));
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

    private static String updateStatement(String migration) {
        int start = migration.indexOf("UPDATE oauth_client");
        int end = migration.indexOf("SET v_updated_rows = ROW_COUNT()", start);
        assertTrue(start >= 0, migration);
        assertTrue(end > start, migration);
        return migration.substring(start, end);
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
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        }
    }
}
