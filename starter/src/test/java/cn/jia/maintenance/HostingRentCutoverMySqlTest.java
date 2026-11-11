package cn.jia.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real one-shot main, account/identity/install SQL, same-transaction schedule and actual static Runtime adoption.
 * Native executor, enrollment and knowledge provider are the existing explicitly synthetic Runtime fixture. */
@EnabledIfEnvironmentVariable(named = "ECO_V0_W02_MYSQL_ISOLATED_FIXTURE", matches = "true")
@EnabledIfEnvironmentVariable(named = "CYF_GSS_RUNTIME_TEST_SOURCE", matches = ".+")
class HostingRentCutoverMySqlTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Test @ResourceLock("SYSTEM_PROPERTIES")
    void actualMainAdoptsOriginalInstallationWithoutPaymentAndReplaysWithoutExtendingTerm() throws Exception {
        String base = System.getenv("ECO_V0_W02_MYSQL_URL");
        assertTrue(base.startsWith("jdbc:mysql://127.0.0.1:"));
        var admin = new JdbcTemplate(new DriverManagerDataSource(base, System.getenv("ECO_V0_W02_MYSQL_USER"), System.getenv("ECO_V0_W02_MYSQL_PASSWORD")));
        assertEquals(Integer.parseInt(System.getenv("ECO_V0_W02_MYSQL_EXPECTED_PORT")), admin.queryForObject("SELECT @@port", Integer.class));
        assertEquals(Path.of(System.getenv("ECO_V0_W02_MYSQL_EXPECTED_DATADIR")).toRealPath(),
                Path.of(admin.queryForObject("SELECT @@datadir", String.class)).toRealPath());
        String database = "cutover_" + UUID.randomUUID().toString().replace("-", "");
        Path scratch = Files.createTempDirectory("cutover-main-"); Process child = null;
        var previous = new LinkedHashMap<String, String>();
        try {
            admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
            String url = base.replace("/mysql?", "/" + database + "?"); assertNotEquals(base, url);
            var ds = new DriverManagerDataSource(url, System.getenv("ECO_V0_W02_MYSQL_USER"), System.getenv("ECO_V0_W02_MYSQL_PASSWORD"));
            var jdbc = new JdbcTemplate(ds);
            Path source = Path.of(System.getenv("CYF_CUTOVER_API_SOURCE")).toRealPath();
            table(jdbc, source.resolve("user/jia-user-mapper/src/test/resources/db/schema.sql"), "CREATE TABLE user_info (");
            Path agentDdl = source.resolve("agent/jia-agent-mapper/src/main/resources/db/schema.sql");
            table(jdbc, agentDdl, "CREATE TABLE IF NOT EXISTS agent_persona_binding (");
            table(jdbc, agentDdl, "CREATE TABLE IF NOT EXISTS agent_runtime (");
            Path identity = source.resolve("agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql");
            table(jdbc, identity, "CREATE TABLE IF NOT EXISTS agent_identity_registry (");
            table(jdbc, identity, "CREATE TABLE IF NOT EXISTS agent_identity_alias (");
            for (String file : List.of("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v1-schema.sql",
                    "agent/jia-agent-mapper/src/main/resources/db/agent-runtime-session-fence-v1.sql",
                    "economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql",
                    "economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql",
                    "task/jia-task-mapper/src/main/resources/db/task-scheduled-work-v1.sql")) {
                new ResourceDatabasePopulator(new FileSystemResource(source.resolve(file))).execute(ds);
            }
            Path runtime = Path.of(System.getenv("CYF_GSS_RUNTIME_TEST_SOURCE")).toRealPath();
            var builder = new ProcessBuilder(System.getenv("CYF_GSS_NODE_BIN"),
                    runtime.resolve("conf/cyf-agent-runtime-v1/test/hosting-java-bridge-fixture.mjs").toString());
            builder.environment().put("CYF_GSS_STATIC_ADOPTION", "1");
            builder.environment().remove("CYF_GSS_INVENTORY_CODEX_BIN");
            builder.redirectError(scratch.resolve("runtime.log").toFile()); child = builder.start();
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));
            var handshake = new java.util.concurrent.FutureTask<String>(reader::readLine); Thread.ofVirtual().start(handshake);
            String line = handshake.get(15, TimeUnit.SECONDS); assertNotNull(line);
            var endpoint = JSON.readValue(line, new TypeReference<Map<String, Object>>() { });
            @SuppressWarnings("unchecked") var manifest = (Map<String, Object>)endpoint.get("adoptionSubject");
            String agent = (String)manifest.get("canonicalAgentId"), installation = (String)manifest.get("installationId");
            String digest = ((String)manifest.get("manifestSha256")).substring(7);
            long at = System.currentTimeMillis() - 1000;
            jdbc.update("INSERT INTO user_info(id,username,jiacn,account_state,auth_epoch) VALUES(1,'fixture-login','fixture-owner','ACTIVE',0)");
            jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES(7,'fixture-owner','wuyong',?,1,1,'0','fixture-client')", agent);
            jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,audit_reason) VALUES(?,'LEGACY_CANONICAL','ACTIVE','fixture-client','fixture-owner','0',7,'original fixture relationship')", agent);
            jdbc.update("INSERT INTO agent_runtime_v1_installation(installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,status,tenant_id,client_id) VALUES(?,?,'1',?,UNHEX(REPEAT('11',32)),1,'ACTIVE','0','fixture-client')", installation, agent, digest);
            jdbc.update("INSERT INTO agent_runtime(agent_id,name,owner_jiacn,persona_code,binding_id,tenant_id,client_id,runtime_installation_id,runtime_host_id,runtime_instance_id,runtime_session_generation) VALUES(?,'fixture','fixture-owner','wuyong',7,'0','fixture-client',?,?,?,1)", agent, installation, endpoint.get("hostId"), endpoint.get("instanceId"));
            jdbc.update("INSERT INTO economy_hosting_rent_plan(plan_id,plan_version,amount_wen,period_seconds,quote_ttl_seconds,currency,status,tenant_id,client_id,create_time) VALUES('agent-self-hosted',1,50,2592000,300,'WEN','ACTIVE','0','fixture-client',?)", at);
            Map<String, Object> originalInstall = jdbc.queryForMap("SELECT * FROM agent_runtime_v1_installation");
            Map<String, Object> originalBinding = jdbc.queryForMap("SELECT * FROM agent_persona_binding");
            Map<String, Object> originalRuntime = jdbc.queryForMap("SELECT * FROM agent_runtime");
            var properties = new LinkedHashMap<String, String>();
            properties.put("spring.config.name", "cutover-fixture-no-shared-application-config");
            properties.put("spring.datasource.url", url); properties.put("spring.datasource.username", System.getenv("ECO_V0_W02_MYSQL_USER"));
            properties.put("spring.datasource.password", System.getenv("ECO_V0_W02_MYSQL_PASSWORD"));
            properties.put("cyf.security.fixed-tenant.tenant-id", "0"); properties.put("cyf.security.fixed-tenant.client-id", "fixture-client");
            properties.put("agent.hosting-rent.enabled", "true"); properties.put("agent.hosting-rent.amount-wen", "500");
            properties.put("agent.hosting-rent.managed.socket-path", (String)endpoint.get("socketPath"));
            properties.put("agent.hosting-rent.managed.runner-uid", endpoint.get("uid").toString());
            properties.put("agent.hosting-rent.managed.socket-group-gid", endpoint.get("gid").toString());
            properties.put("agent.hosting-rent.managed.tenant-id", "0"); properties.put("agent.hosting-rent.managed.client-id", "fixture-client");
            properties.put("agent.hosting-rent.managed.owner-jiacn", "fixture-owner");
            properties.put("agent.hosting-rent.control.local-host-id", endpoint.get("hostId").toString());
            properties.put("agent.hosting-rent.control.self-hosted-host-ids[0]", endpoint.get("hostId").toString());
            properties.put("economy.preview.enabled", "true");
            properties.put("economy.preview.allowed-scopes[0].tenant-id", "0"); properties.put("economy.preview.allowed-scopes[0].client-id", "fixture-client");
            properties.forEach((key, value) -> { previous.put(key, System.getProperty(key)); System.setProperty(key, value); });
            Path input = scratch.resolve("fixed-input.json");
            Files.writeString(input, JSON.writeValueAsString(new HostingRentCutover.Batch(at, List.of(
                    new HostingRentCutover.Subject("fixture-login", "fixture-owner", agent, installation)))));
            HostingRentCutover.main(new String[]{"--preview", input.toString(), scratch.resolve("preview.jsonl").toString()});
            assertEquals(0, count(jdbc, "economy_hosting_lease")); assertEquals(0, count(jdbc, "economy_account"));
            for (int retry = 0; retry < 2; retry++) {
                Path receipt = scratch.resolve("apply-" + retry + ".jsonl");
                HostingRentCutover.main(new String[]{"--apply", input.toString(), receipt.toString()});
                assertTrue(Files.readAllLines(receipt).getLast().contains("COMPLETED"));
                assertEquals(1, count(jdbc, "economy_hosting_lease")); assertEquals(1, count(jdbc, "economy_hosting_provisioning_intent"));
                assertEquals(1, count(jdbc, "task_scheduled_work")); assertEquals(0, count(jdbc, "economy_transaction"));
                assertEquals(0L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE owner_type='USER'", Long.class));
                var lease = jdbc.queryForMap("SELECT * FROM economy_hosting_lease");
                assertEquals(at, ((Number)lease.get("paid_from")).longValue());
                assertEquals(at + 2592000000L, ((Number)lease.get("paid_through")).longValue());
                assertEquals(50L, ((Number)lease.get("amount_wen")).longValue());
                assertEquals("TRANSITION", jdbc.queryForObject("SELECT settlement_kind FROM economy_hosting_provisioning_intent", String.class));
            }
            assertEquals(originalBinding, jdbc.queryForMap("SELECT * FROM agent_persona_binding"));
            assertEquals(originalRuntime, jdbc.queryForMap("SELECT * FROM agent_runtime"));
            var finalInstall = jdbc.queryForMap("SELECT * FROM agent_runtime_v1_installation");
            assertArrayEquals((byte[])originalInstall.remove("enrollment_secret_hash"), (byte[])finalInstall.remove("enrollment_secret_hash"));
            assertEquals(originalInstall, finalInstall);
        } finally {
            previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
            if (child != null) {
                child.getOutputStream().close();
                if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroy(); child.waitFor(); }
            }
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }
    private static int count(JdbcTemplate jdbc, String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static void table(JdbcTemplate jdbc, Path file, String marker) throws Exception {
        String ddl = Files.readString(file); int start = ddl.indexOf(marker); assertTrue(start >= 0, marker);
        jdbc.execute(ddl.substring(start, ddl.indexOf(";\n", start)));
    }
}
