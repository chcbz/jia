package cn.jia.agent.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2RegistrationMigrationTest {
    private static final String PHASE_ONE = "agent-runtime-v2-registration-manual-20260930.sql";
    private static final String ACTIVATION = "agent-runtime-v2-registration-activation-manual-20260930.sql";

    @Test void phaseOneIsManualAdditiveNullableAndContainsNoDataOrActivationChecks() throws Exception {
        String sql = read(PHASE_ONE); String lower = sql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("PHASE 1 ONLY"));
        assertTrue(sql.contains("REVIEW/RUN MANUALLY ONLY"));
        assertNoDataSecretsOrForeignKeys(lower);
        for (String table : new String[]{"agent_runtime_v2_registration_authority",
                "agent_runtime_v2_registration_receipt", "agent_runtime_v2_registration_request",
                "agent_runtime_v2_reconnect_challenge"}) assertTrue(sql.contains("CREATE TABLE " + table));
        for (String token : new String[]{"ENGINE=InnoDB", "COLLATE=utf8mb4_0900_bin",
                "active_credential_reference", "active_business_subject", "generation_snapshot_sha256",
                "execution_origin_snapshot_sha256", "uk_runtime_v2_binding_live_business",
                "uk_binding_generation_registration_receipt", "chk_registration_authority_canonical",
                "chk_reconnect_challenge_canonical"}) assertTrue(sql.contains(token), token);
        for (String column : new String[]{"registration_subject_sha256 BINARY(32) NULL",
                "generation_snapshot_sha256 BINARY(32) NULL",
                "dispatch_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL",
                "execution_origin_snapshot_sha256 BINARY(32) NULL",
                "holder_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL",
                "requested_holder_generation_fact_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL"}) {
            assertTrue(sql.contains(column), column);
        }
        for (String deferred : activationChecks()) assertFalse(sql.contains(deferred), deferred);
    }

    @Test void activationIsSeparateManualCheckOnlyPhase() throws Exception {
        String sql = read(ACTIVATION); String lower = sql.toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("activation-only CHECK phase"));
        assertTrue(sql.contains("If any proof query reports an incomplete reachable row, STOP"));
        assertNoDataSecretsOrForeignKeys(lower);
        assertFalse(lower.contains("create table"));
        assertFalse(lower.contains("add column"));
        assertFalse(lower.contains("add unique"));
        for (String check : activationChecks()) assertTrue(sql.contains(check), check);
        assertEquals(8, count(lower, "alter table "));
        assertEquals(8, count(lower, "add constraint "));
    }

    @Test void exactActivationChecksKeepHistoricalRowsButRejectReachableNullBypass() throws Exception {
        JdbcTemplate jdbc = h2();
        jdbc.execute("""
                CREATE TABLE binding_probe(
                  contract_version SMALLINT NOT NULL,binding_generation BIGINT NOT NULL,
                  reconnect_verifier BINARY(32),registration_subject_sha256 BINARY(32),
                  registration_authority_id VARCHAR(36),registration_authority_revision BIGINT,
                  process_public_key_spki BINARY(44),process_public_key_sha256 BINARY(32),
                  verifier_version BIGINT,verifier_key_id VARCHAR(100),
                  current_registration_receipt_id VARCHAR(36),current_registration_receipt_sha256 BINARY(32))
                """);
        jdbc.update("INSERT INTO binding_probe(contract_version,binding_generation) VALUES(1,0)");
        jdbc.execute("ALTER TABLE binding_probe ADD CONSTRAINT chk_binding CHECK ("
                + checkClause(read(ACTIVATION), "chk_runtime_v2_binding_registration") + ")");
        assertThrows(RuntimeException.class,
                () -> jdbc.update("INSERT INTO binding_probe(contract_version,binding_generation) VALUES(2,0)"));
        assertEquals(1, jdbc.update("""
                INSERT INTO binding_probe VALUES(2,0,?,?,?,?,?,?,?,?,?,?)
                """, new byte[32], new byte[32], "66666666-6666-4666-8666-666666666666", 0L,
                x25519Spki(), new byte[32], 1L, "verifier-key",
                "77777777-7777-4777-8777-777777777777", new byte[32]));

        jdbc.execute("""
                CREATE TABLE inbox_probe(protocol_version SMALLINT,runtime_dispatch_id VARCHAR(36),
                  dispatch_generation_fact_id VARCHAR(36),dispatch_generation_snapshot_sha256 BINARY(32),
                  execution_origin_snapshot_sha256 BINARY(32))
                """);
        jdbc.update("INSERT INTO inbox_probe(protocol_version,runtime_dispatch_id) VALUES(2,NULL)");
        jdbc.execute("ALTER TABLE inbox_probe ADD CONSTRAINT chk_inbox CHECK ("
                + checkClause(read(ACTIVATION), "chk_inbox_runtime_v2_origin_evidence") + ")");
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO inbox_probe(protocol_version,runtime_dispatch_id) VALUES(2,'88888888-8888-4888-8888-888888888888')"));
        assertEquals(1, jdbc.update("INSERT INTO inbox_probe VALUES(2,?,?,?,?)",
                "88888888-8888-4888-8888-888888888888",
                "99999999-9999-4999-8999-999999999999", new byte[32], new byte[32]));
    }

    @Test void phaseOneBusinessUniquenessPreservesHistoricalNullEvidence() throws Exception {
        String sql = read(PHASE_ONE);
        assertTrue(sql.contains("status <> 'REVOKED' AND contract_version = 2\n        AND registration_subject_sha256 IS NOT NULL"));

        JdbcTemplate jdbc = h2();
        jdbc.execute("""
                CREATE TABLE binding_business_probe(
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  status VARCHAR(20) NOT NULL,contract_version SMALLINT NOT NULL,
                  registration_subject_sha256 BINARY(32),tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(100) NOT NULL,
                  agent_id VARCHAR(100) NOT NULL,
                  live_business_subject VARCHAR(500) GENERATED ALWAYS AS
                    (CASE WHEN status <> 'REVOKED' AND contract_version = 2
                                AND registration_subject_sha256 IS NOT NULL
                      THEN CONCAT(LPAD(OCTET_LENGTH(tenant_id),3,'0'),':',tenant_id,
                                  LPAD(OCTET_LENGTH(client_id),3,'0'),':',client_id,
                                  LPAD(OCTET_LENGTH(owner_jiacn),3,'0'),':',owner_jiacn,
                                  LPAD(OCTET_LENGTH(agent_id),3,'0'),':',agent_id)
                      ELSE NULL END),
                  CONSTRAINT uk_binding_business UNIQUE(live_business_subject))
                """);
        String insert = "INSERT INTO binding_business_probe(status,contract_version,registration_subject_sha256,tenant_id,client_id,owner_jiacn,agent_id) VALUES(?,?,?,?,?,?,?)";
        Object[] historical = {"ACTIVE", 2, null, "tenant", "client", "owner", "agent"};
        assertEquals(1, jdbc.update(insert, historical));
        assertEquals(1, jdbc.update(insert, historical));
        byte[] subject = new byte[32]; subject[0] = 1;
        assertEquals(1, jdbc.update(insert, "ACTIVE", 2, subject, "tenant", "client", "owner", "agent"));
        assertThrows(RuntimeException.class, () -> jdbc.update(insert,
                "DISCONNECTED", 2, new byte[32], "tenant", "client", "owner", "agent"));
        assertEquals(1, jdbc.update(insert,
                "REVOKED", 2, new byte[32], "tenant", "client", "owner", "agent"));
    }

    @Test void generatedBusinessSubjectUsesLengthPrefixesAndCredentialRotationCollides() {
        JdbcTemplate jdbc = h2();
        jdbc.execute("""
                CREATE TABLE authority_probe(
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  credential_reference VARCHAR(200) NOT NULL,status VARCHAR(20) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(100) NOT NULL,agent_id VARCHAR(100) NOT NULL,
                  active_business_subject VARCHAR(500) GENERATED ALWAYS AS
                    (CASE WHEN status='ACTIVE' THEN CONCAT(
                      LPAD(OCTET_LENGTH(tenant_id),3,'0'),':',tenant_id,
                      LPAD(OCTET_LENGTH(client_id),3,'0'),':',client_id,
                      LPAD(OCTET_LENGTH(owner_jiacn),3,'0'),':',owner_jiacn,
                      LPAD(OCTET_LENGTH(agent_id),3,'0'),':',agent_id) ELSE NULL END),
                  CONSTRAINT uk_authority_business UNIQUE(active_business_subject))
                """);
        assertEquals(1, jdbc.update("INSERT INTO authority_probe(credential_reference,status,tenant_id,client_id,owner_jiacn,agent_id) VALUES('key-1','ACTIVE','1','a:b','c','d')"));
        assertEquals(1, jdbc.update("INSERT INTO authority_probe(credential_reference,status,tenant_id,client_id,owner_jiacn,agent_id) VALUES('key-2','ACTIVE','1','a','b:c','d')"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "INSERT INTO authority_probe(credential_reference,status,tenant_id,client_id,owner_jiacn,agent_id) VALUES('rotated','ACTIVE','1','a:b','c','d')"));
        assertEquals(1, jdbc.update("INSERT INTO authority_probe(credential_reference,status,tenant_id,client_id,owner_jiacn,agent_id) VALUES('retired','REVOKED','1','a:b','c','d')"));
    }

    @Test void mapperJarContainsBothManualPhasesAndNoOrig() throws Exception {
        Path jar = Files.list(Path.of(System.getProperty("user.dir"), "..", "jia-agent-mapper", "build", "libs"))
                .filter(path -> path.toString().endsWith(".jar")).findFirst().orElseThrow();
        try (FileSystem fileSystem = FileSystems.newFileSystem(jar)) {
            assertTrue(Files.exists(fileSystem.getPath("/db/" + PHASE_ONE)));
            assertTrue(Files.exists(fileSystem.getPath("/db/" + ACTIVATION)));
            assertTrue(Files.walk(fileSystem.getPath("/")).noneMatch(path -> path.toString().endsWith(".orig")));
        }
    }

    private static String read(String name) throws Exception {
        Path path = Path.of(System.getProperty("user.dir"), "..", "jia-agent-mapper", "src", "main", "resources", "db", name).normalize();
        assertTrue(Files.isRegularFile(path), path.toString());
        return Files.readString(path);
    }
    private static void assertNoDataSecretsOrForeignKeys(String lower) {
        for (String token : new String[]{"insert into ", "update ", "delete from ", "replace into ",
                "private key", "begin private", "backfill "}) assertFalse(lower.contains(token), token);
        assertFalse(Pattern.compile("\\bforeign\\s+key\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(lower).find(), "foreign key DDL");
    }
    private static String[] activationChecks() {
        return new String[]{"chk_runtime_v2_binding_registration", "chk_binding_generation_registration",
                "chk_inbox_runtime_v2_origin_evidence", "chk_ack_receipt_snapshot_links",
                "chk_execution_lease_snapshot_links", "chk_execution_grant_snapshot_links",
                "chk_execution_anchor_snapshot_links", "chk_execution_request_snapshot_links"};
    }
    private static int count(String value, String token) {
        int count = 0; for (int offset = 0; (offset = value.indexOf(token, offset)) >= 0; offset += token.length()) count++;
        return count;
    }
    private static String checkClause(String sql, String name) {
        Matcher start = Pattern.compile("ADD CONSTRAINT\\s+" + Pattern.quote(name) + "\\s+CHECK\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(start.find(), name);
        int depth = 1; boolean quoted = false;
        for (int index = start.end(); index < sql.length(); index++) {
            char value = sql.charAt(index);
            if (value == '\'' && (index == 0 || sql.charAt(index - 1) != '\\')) quoted = !quoted;
            else if (!quoted && value == '(') depth++;
            else if (!quoted && value == ')' && --depth == 0) return sql.substring(start.end(), index);
        }
        throw new AssertionError("unterminated CHECK " + name);
    }
    private static JdbcTemplate h2() {
        return new JdbcTemplate(new SingleConnectionDataSource(
                "jdbc:h2:mem:r2b1_" + UUID.randomUUID() + ";MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "", true));
    }
    private static byte[] x25519Spki() {
        byte[] value = new byte[44];
        byte[] prefix = java.util.HexFormat.of().parseHex("302a300506032b656e032100");
        System.arraycopy(prefix, 0, value, 0, prefix.length); return value;
    }
}
