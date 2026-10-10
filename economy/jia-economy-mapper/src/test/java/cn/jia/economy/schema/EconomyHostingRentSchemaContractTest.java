package cn.jia.economy.schema;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyHostingRentSchemaContractTest {
    @Test
    void hostingSchemaIsSeparateAdditiveAndContainsNoPlanDataOrW02Rewrite() throws Exception {
        Path root = apiRoot();
        String hosting = Files.readString(root.resolve(
                "economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql"),
                StandardCharsets.UTF_8);
        String foundation = Files.readString(root.resolve(
                "economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql"),
                StandardCharsets.UTF_8);

        List<String> tables = List.of("economy_hosting_rent_plan", "economy_hosting_rent_quote",
                "economy_hosting_lease", "economy_hosting_provisioning_intent", "economy_hosting_reprovision");
        for (String table : tables) {
            assertEquals(1, occurrences(hosting, "CREATE TABLE IF NOT EXISTS " + table + " "), table);
            assertFalse(foundation.contains(table), table);
        }
        assertEquals(5, occurrences(hosting, "CREATE TABLE IF NOT EXISTS "));
        String lower = hosting.toLowerCase();
        assertFalse(lower.contains("insert into"));
        assertFalse(lower.contains("update "));
        assertFalse(lower.contains("delete from"));
        assertFalse(lower.contains("alter table"));
        assertTrue(hosting.contains("principal_type = 'USER'"));
        assertTrue(hosting.contains("OCTET_LENGTH(reserve_idempotency_key) = 36"));
        assertTrue(hosting.contains("'PROVISIONING_UNKNOWN'"));
        assertTrue(hosting.contains("'FAILED_NO_EFFECT'"));
        assertTrue(hosting.contains("'SERVICE_READY'"));
        assertTrue(hosting.contains("service_ready_at >= reserved_at"));
        assertTrue(hosting.contains("UNIQUE KEY uk_hosting_lease_agent (tenant_id,client_id,agent_id,live_slot)"));
        assertTrue(hosting.contains("status = 'REFUNDED' AND live_slot IS NULL"));
        assertTrue(hosting.contains("live_slot IS NOT NULL AND live_slot = 1"));
    }

    @Test void runtimeLinkageFreshSchemaAndExplicitMigrationAreAdditiveAndEquivalent() throws Exception {
        Path db = apiRoot().resolve("economy/jia-economy-mapper/src/main/resources/db");
        String fresh = Files.readString(db.resolve("economy-v0-hosting-rent.sql")).replaceAll("\\s+", " ");
        String migration = Files.readString(db.resolve("migration-gss-hosting-runtime-v1-20261010.sql"));
        String normalizedMigration = migration.replaceAll("\\s+", " ");
        for (String token : List.of("runtime_installation_id VARCHAR(100) NULL", "runtime_manifest_sha256 VARCHAR(64) NULL",
                "runtime_provision_generation BIGINT NOT NULL DEFAULT 0", "runtime_target_generation BIGINT NULL",
                "uk_hosting_intent_installation", "chk_hosting_intent_runtime", "chk_hosting_reprovision_runtime",
                "runtime_installation_id IS NULL AND runtime_manifest_sha256 IS NULL AND runtime_provision_generation = 0",
                "runtime_target_generation IS NULL OR runtime_target_generation > 0")) {
            assertTrue(fresh.contains(token), token); assertTrue(normalizedMigration.contains(token), token);
        }
        String statements = migration.replaceAll("(?m)--[^\n]*", "").toLowerCase();
        assertEquals(2, occurrences(statements, "alter table"));
        for (String forbidden : List.of("insert into", "update ", "delete from", "drop ", "truncate "))
            assertFalse(statements.contains(forbidden), forbidden);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("economy"))) return candidate;
        }
        throw new IllegalStateException("Cannot locate API worktree root");
    }
}
