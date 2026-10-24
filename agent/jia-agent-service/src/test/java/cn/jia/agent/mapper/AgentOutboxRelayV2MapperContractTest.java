package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOutboxRelayV2MapperContractTest {
    @Test
    void everyDiscoveryLaneIsV2AndExactlyTenantDomainPoolEpochScoped() throws Exception {
        for (String name : new String[] {
                "selectCorruptCandidates", "selectDueCandidates", "selectStaleCandidates"}) {
            String sql = sql(name);
            assertScope(sql, "o.");
            assertTrue(sql.contains("o.protocol_version=2"), name);
            assertTrue(sql.contains("o.producer_deployment_id is not null"), name);
        }
        String stale = sql("selectStaleCandidates");
        assertTrue(stale.contains("cast(o.claimed_deployment_id as binary)="
                + "cast(#{deploymentid} as binary)"));
        assertTrue(stale.contains("o.claimed_worker_generation=#{workergeneration}"));
    }

    @Test
    void everyLockAndMutationUsesExactScopeAndV2Fence() throws Exception {
        for (String name : new String[] {
                "selectDeliveryForUpdate", "selectOutboxForUpdate",
                "selectPreviousAttemptOutboxesForUpdate", "selectOutboxForQuarantine",
                "claimDelivery", "claimOutbox", "disposeDelivery", "disposeOutbox",
                "quarantineDelivery", "quarantineOutbox"}) {
            String sql = sql(name);
            assertScope(sql, "");
            assertTrue(sql.contains("protocol_version=2"), name);
        }
    }

    @Test
    void everyClaimantCasIsByteExactAndGenerationBound() throws Exception {
        for (String name : new String[] {
                "selectOutboxForQuarantine", "claimDelivery", "claimOutbox",
                "disposeDelivery", "disposeOutbox", "quarantineDelivery", "quarantineOutbox"}) {
            String sql = sql(name);
            assertTrue(sql.contains("cast(claimed_deployment_id as binary)="
                    + "cast(#{deploymentid} as binary)"), name);
            assertTrue(sql.contains("octet_length(claimed_deployment_id)="
                    + "octet_length(#{deploymentid})"), name);
            assertTrue(sql.contains("claimed_worker_generation=#{workergeneration}"), name);
        }
    }

    @Test
    void manualMigrationHasOneStatementPerTableAndCanonicalPoolConstraints() throws Exception {
        String sql = Files.readString(Path.of("../jia-agent-mapper/src/main/resources/db/"
                + "agent-outbox-relay-v2-manual-20260925.sql"));
        assertEquals(1, occurrences(sql, "ALTER TABLE agent_command_delivery"));
        assertEquals(1, occurrences(sql, "ALTER TABLE agent_outbox_event"));
        assertEquals(2, occurrences(sql,
                "execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'"));
        assertEquals(1, occurrences(sql, "-- Post-check only."));
    }

    private static void assertScope(String sql, String alias) {
        for (String column : new String[] {
                "tenant_id", "data_domain_id", "execution_pool_id", "module_id"}) {
            String parameter = switch (column) {
                case "tenant_id" -> "tenantid";
                case "data_domain_id" -> "datadomainid";
                case "execution_pool_id" -> "executionpoolid";
                default -> "moduleid";
            };
            assertTrue(sql.contains("cast(" + alias + column + " as binary)="
                    + "cast(#{" + parameter + "} as binary)"), column);
            assertTrue(sql.contains("octet_length(" + alias + column + ")="
                    + "octet_length(#{" + parameter + "})"), column);
        }
        assertTrue(sql.contains(alias + "admission_epoch=#{admissionepoch}"));
        assertTrue(sql.contains(alias + "migration_epoch=#{migrationepoch}"));
    }

    private static String sql(String name) throws Exception {
        for (Method method : AgentOutboxRelayMapper.class.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            Select select = method.getAnnotation(Select.class);
            if (select != null) return normalize(String.join(" ", select.value()));
            Update update = method.getAnnotation(Update.class);
            if (update != null) return normalize(String.join(" ", update.value()));
        }
        throw new NoSuchMethodException(name);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }
}
