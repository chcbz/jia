package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV2RegistrationSchemaGateTest {
    @Test void generatedMetadataExpectedRetainedAndSyntheticPlainFormatsReachTheActualGate() throws Exception {
        Map<String,String> retained = retainedGeneratedFields();
        for (String shape : List.of("expected", "retained", "plain")) {
            Fake catalog = canonical();
            if (!shape.equals("expected")) catalog.columns.forEach((table, columns) -> columns.replaceAll(c -> {
                if (c.generationExpression().isEmpty()) return c;
                String plain = "(" + c.generationExpression().replace("octet_length(", "length(") + ")";
                return generated(c, shape.equals("retained") && table.equals("agent_runtime_v2_binding")
                        ? retained.getOrDefault(c.name(), plain) : plain);
            }));
            catalog.columns.get("agent_runtime_v2_binding").add(0, new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                    "legal_baseline_column", "bigint", "bigint", false, null, null, "", ""));
            Map<String,List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> before = new HashMap<>();
            catalog.columns.forEach((table, columns) -> before.put(table, List.copyOf(columns)));
            assertDoesNotThrow(() -> new AgentRuntimeV2RegistrationSchemaGate(catalog).afterPropertiesSet(), shape);
            assertEquals(before, catalog.columns, "raw records and legal additive baseline extras remain untouched");
            assertTrue(catalog.columns.values().stream().flatMap(List::stream)
                    .noneMatch(c -> c.generationExpression().startsWith("generated:")));
        }
    }

    @Test void generatedDriftFailsForBothAdditiveAndExactTablesAndEveryOtherAttributeRemainsExact() {
        for (String table : List.of("agent_runtime_v2_binding", AgentRuntimeV2RegistrationSchemaGate.AUTHORITY)) {
            String name = table.equals("agent_runtime_v2_binding") ? "live_business_subject" : "active_business_subject";
            var original = AgentRuntimeV2RegistrationSchemaGate.expectedColumns().get(table).stream()
                    .filter(c -> c.name().equals(name)).findFirst().orElseThrow();
            String raw = original.generationExpression();
            String literal = table.equals("agent_runtime_v2_binding") ? "REVOKED" : "ACTIVE";
            for (String expression : List.of(raw.replace("'" + literal + "'", "'" + literal.toLowerCase(Locale.ROOT) + "'"),
                    raw.replace("`status`", "`STATUS`"), raw.replace("octet_length", "char_length"),
                    raw.replace("sha2", "hex"), raw.replace(",256)", ",224)"), raw.replace(",3,", ",4,"),
                    raw.replace("`tenant_id`", "`client_id`"), raw.replace("then sha2", "then lower"),
                    raw.replace("else NULL", "else 'ACTIVE'"), raw + ";", "generated:" + raw,
                    "case when x=1 then x end", "if(x=1,1,null)", "")) {
                assertNotEquals(raw, expression);
                Fake changed = canonical(); replaceColumn(changed, table, name, c -> generated(c, expression)); assertDenied(changed);
            }
            for (String field : List.of("name", "dataType", "columnType", "nullable", "default", "collation", "extra")) {
                Fake changed = canonical(); replaceColumn(changed, table, name,
                        c -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                                field.equals("name") ? "forged" : c.name(), field.equals("dataType") ? "varchar" : c.dataType(),
                                field.equals("columnType") ? "char(63)" : c.columnType(), field.equals("nullable") ? !c.nullable() : c.nullable(),
                                field.equals("default") ? "forged" : c.defaultValue(), field.equals("collation") ? "utf8mb4_0900_ai_ci" : c.collation(),
                                field.equals("extra") ? "VIRTUAL GENERATED" : c.extra(), c.generationExpression()));
                assertDenied(changed);
            }
        }
        Fake guard = canonical(); replaceColumn(guard, "agent_runtime_v2_binding", "live_business_subject",
                c -> generated(c, c.generationExpression().replace(" and (`registration_subject_sha256` is not null)", "")));
        assertDenied(guard);
    }

    @Test void generatedComparisonPreservesExactAndAdditiveMembershipOrderAndDuplicateRejection() {
        for (String table : List.of("agent_runtime_v2_binding", AgentRuntimeV2RegistrationSchemaGate.AUTHORITY)) {
            for (String mutation : List.of("missing", "extra", "reordered", "duplicate")) {
                Fake changed = canonical(); var columns = changed.columns.get(table);
                switch (mutation) {
                    case "missing" -> columns.removeLast();
                    case "extra" -> columns.add(new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                            "legal_baseline_column", "bigint", "bigint", false, null, null, "", ""));
                    case "reordered" -> java.util.Collections.swap(columns, 0, 1);
                    default -> columns.set(1, columns.getFirst());
                }
                if (table.equals("agent_runtime_v2_binding") && mutation.equals("extra"))
                    assertDoesNotThrow(() -> new AgentRuntimeV2RegistrationSchemaGate(changed).afterPropertiesSet());
                else assertDenied(changed);
            }
        }
    }

    private static AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition generated(
            AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition c, String expression) {
        return new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(c.name(), c.dataType(), c.columnType(),
                c.nullable(), c.defaultValue(), c.collation(), c.extra(), expression);
    }
    private static Map<String,String> retainedGeneratedFields() throws Exception {
        try (var stream = AgentRuntimeV2RegistrationSchemaGateTest.class.getResourceAsStream("runtime-v2-generated-columns-native-20261008.tsv")) {
            assertNotNull(stream); Map<String,String> result = new LinkedHashMap<>();
            for (String line : new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(s -> !s.startsWith("#") && !s.isBlank()).toList()) {
                String[] fields = line.split("\t", -1); assertEquals(3, fields.length);
                assertEquals(fields[1], java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(fields[2].getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                assertNull(result.put(fields[0], fields[2]));
            }
            assertEquals(2, result.size()); return result;
        }
    }

    @Test void exactCatalogAcceptedAndExistingBaselineMetadataMayCoexist() {
        Fake catalog = canonical();
        String existing = "agent_runtime_v2_binding";
        catalog.columns.get(existing).add(0, new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                "baseline_column", "bigint", "bigint", false, null, null, "", ""));
        catalog.indexes.get(existing).put("baseline_index",
                new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false, List.of("baseline_column")));
        catalog.checks.get(existing).put("baseline_check",
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition("baseline_column >= 0", true));
        assertDoesNotThrow(() -> new AgentRuntimeV2RegistrationSchemaGate(catalog).afterPropertiesSet());
        assertTrue(catalog.reads > 0);
    }

    @Test void rejectsProductTableEngineCollationAndForeignKeyDrift() {
        Fake product = canonical(); product.product = "PostgreSQL"; assertDenied(product);
        Fake missing = canonical(); missing.tables.remove(AgentRuntimeV2RegistrationSchemaGate.RECEIPT); assertDenied(missing);
        Fake engine = canonical(); engine.tablesByName.put(AgentRuntimeV2RegistrationSchemaGate.REQUEST,
                new AgentRuntimeV2RegistrationSchemaGate.TableDefinition("MyISAM", "utf8mb4_0900_bin")); assertDenied(engine);
        Fake collation = canonical(); collation.tablesByName.put(AgentRuntimeV2RegistrationSchemaGate.REQUEST,
                new AgentRuntimeV2RegistrationSchemaGate.TableDefinition("InnoDB", "utf8mb4_general_ci")); assertDenied(collation);
        Fake foreignKey = canonical(); foreignKey.foreignKeys.put(AgentRuntimeV2RegistrationSchemaGate.REQUEST, 1); assertDenied(foreignKey);
    }

    @Test void rejectsMissingExtraWrongAndGeneratedColumnDrift() {
        Fake missing = canonical(); missing.columns.get(AgentRuntimeV2RegistrationSchemaGate.RECEIPT).remove(2); assertDenied(missing);
        Fake extra = canonical(); extra.columns.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).add(
                new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition("attacker", "varchar", "varchar(1)", true, null, "utf8mb4_0900_bin", "", "")); assertDenied(extra);
        Fake wrongType = canonical(); replaceColumn(wrongType, AgentRuntimeV2RegistrationSchemaGate.REQUEST,
                "request_wire_sha256", column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), "varbinary", "varbinary(32)", column.nullable(), column.defaultValue(),
                        column.collation(), column.extra(), column.generationExpression())); assertDenied(wrongType);
        Fake wrongNullability = canonical(); replaceColumn(wrongNullability, AgentRuntimeV2RegistrationSchemaGate.CHALLENGE,
                "challenge_digest", column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), column.dataType(), column.columnType(), true, column.defaultValue(),
                        column.collation(), column.extra(), column.generationExpression())); assertDenied(wrongNullability);
        Fake wrongProvenanceDefault = canonical(); replaceColumn(wrongProvenanceDefault,
                "agent_command_v2_execution_grant", "evidence_provenance",
                column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), column.dataType(), column.columnType(), column.nullable(),
                        "LEGACY_PRE_R2B1", column.collation(), column.extra(),
                        column.generationExpression())); assertDenied(wrongProvenanceDefault);
        Fake wrongProvenanceCollation = canonical(); replaceColumn(wrongProvenanceCollation,
                "agent_consumer_inbox", "evidence_provenance",
                column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), column.dataType(), column.columnType(), column.nullable(),
                        column.defaultValue(), "utf8mb4_general_ci", column.extra(),
                        column.generationExpression())); assertDenied(wrongProvenanceCollation);
        Fake generated = canonical(); replaceColumn(generated, AgentRuntimeV2RegistrationSchemaGate.AUTHORITY,
                "active_business_subject", column -> new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        column.name(), column.dataType(), column.columnType(), column.nullable(), column.defaultValue(),
                        column.collation(), column.extra(), column.generationExpression() + " + 'forged'")); assertDenied(generated);
    }

    @Test void additiveCatalogColumnDefinitionsMatchManualDdl() throws Exception {
        Path migration = Path.of(System.getProperty("user.dir"), "..", "jia-agent-mapper",
                "src", "main", "resources", "db",
                "agent-runtime-v2-registration-manual-20260930.sql").normalize();
        Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> ddl =
                additiveColumns(Files.readString(migration));
        for (String table : Set.of("agent_consumer_inbox", "agent_command_v2_ack_receipt",
                "agent_command_v2_execution_lease_fact", "agent_command_v2_execution_grant",
                "agent_command_v2_execution_continuation_anchor",
                "agent_command_v2_execution_request")) {
            assertEquals(AgentRuntimeV2RegistrationSchemaGate.expectedColumns().get(table),
                    ddl.get(table), table);
        }
    }

    @Test void rejectsMissingExtraWrongOrderAndUniquenessIndexDrift() {
        Fake missing = canonical(); missing.indexes.get(AgentRuntimeV2RegistrationSchemaGate.RECEIPT)
                .remove("uk_registration_receipt_generation_fact"); assertDenied(missing);
        Fake extra = canonical(); extra.indexes.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(
                "idx_attacker", new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false, List.of("status"))); assertDenied(extra);
        Fake order = canonical(); order.indexes.get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).put(
                "idx_registration_request_subject", new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false,
                        List.of("request_kind", "registration_subject_sha256", "decided_at"))); assertDenied(order);
        Fake uniqueness = canonical(); uniqueness.indexes.get(AgentRuntimeV2RegistrationSchemaGate.CHALLENGE).put(
                "uk_reconnect_challenge_id", new AgentRuntimeV2RegistrationSchemaGate.IndexDefinition(false,
                        List.of("challenge_id"))); assertDenied(uniqueness);
    }

    @Test void rejectsMissingExtraUnenforcedAndWeakenedChecks() {
        Fake missing = canonical(); missing.checks.get(AgentRuntimeV2RegistrationSchemaGate.RECEIPT)
                .remove("chk_registration_receipt_canonical"); assertDenied(missing);
        Fake extra = canonical(); extra.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(
                "chk_attacker", new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition("1=1", true)); assertDenied(extra);
        Fake unenforced = canonical(); String name = "chk_registration_request_canonical";
        String clause = AgentRuntimeV2RegistrationSchemaGate.expectedChecks()
                .get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).get(name);
        unenforced.checks.get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(clause, false)); assertDenied(unenforced);
        Fake weakened = canonical(); weakened.checks.get(AgentRuntimeV2RegistrationSchemaGate.REQUEST).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition("(" + clause + ") OR 1=1", true)); assertDenied(weakened);
    }

    @Test void rejectsAnyLegacyOrCrossTableActivationReadinessViolation() {
        Fake legacyReachable = canonical(); legacyReachable.readinessViolations = 1; assertDenied(legacyReachable);
        Fake brokenEvidenceGraph = canonical(); brokenEvidenceGraph.readinessViolations = 7; assertDenied(brokenEvidenceGraph);
    }

    @Test void jdbcReadinessQueryPreservesLegacyHistoryAndRejectsEveryBrokenEnforcedLink() {
        JdbcTemplate jdbc = readinessJdbc();
        byte[] hash = new byte[32]; hash[0] = 7;
        String legacyBinding = "10000000-0000-4000-8000-000000000001";
        String legacyFact = "10000000-0000-4000-8000-000000000002";
        seedLegacyEvidenceHistory(jdbc, legacyBinding, legacyFact);
        assertEquals(0, readinessViolations(jdbc), "revoked legacy evidence is history, not authority");

        String binding = "20000000-0000-4000-8000-000000000001";
        String fact = "20000000-0000-4000-8000-000000000002";
        String receipt = "20000000-0000-4000-8000-000000000003";
        String runtime = "20000000-0000-4000-8000-000000000004";
        String dispatch = "20000000-0000-4000-8000-000000000005";
        String lease = "20000000-0000-4000-8000-000000000006";
        String grant = "20000000-0000-4000-8000-000000000007";
        seedCompleteEnforcedEvidenceGraph(jdbc, binding, fact, receipt, runtime,
                dispatch, lease, grant, hash);
        assertEquals(0, readinessViolations(jdbc));

        String liveLegacy = "30000000-0000-4000-8000-000000000001";
        jdbc.update("INSERT INTO agent_runtime_v2_binding VALUES(2,'LEGACY_PRE_R2B1','ACTIVE',?,0,?,NULL,NULL)",
                liveLegacy, runtime);
        assertTrue(readinessViolations(jdbc) > 0, "live legacy binding must block activation");
        jdbc.update("DELETE FROM agent_runtime_v2_binding WHERE runtime_binding_id=?", liveLegacy);

        jdbc.update("UPDATE agent_runtime_v2_binding_generation_fact SET evidence_provenance='LEGACY_PRE_R2B1' WHERE binding_generation_fact_id=?", fact);
        assertTrue(readinessViolations(jdbc) > 0, "enforced binding cannot depend on legacy generation");
        jdbc.update("UPDATE agent_runtime_v2_binding_generation_fact SET evidence_provenance='R2B1_ENFORCED' WHERE binding_generation_fact_id=?", fact);

        jdbc.update("DELETE FROM agent_runtime_v2_registration_receipt WHERE registration_receipt_id=?", receipt);
        assertTrue(readinessViolations(jdbc) > 0, "missing immutable registration receipt must block activation");
        jdbc.update("INSERT INTO agent_runtime_v2_registration_receipt VALUES(?,?,?,?,?)",
                receipt, hash, fact, binding, 0L);

        String legacyDispatch = "30000000-0000-4000-8000-000000000002";
        jdbc.update("""
                INSERT INTO agent_consumer_inbox VALUES(
                  'R2B1_ENFORCED',2,?,?,?,?,0,?,NULL,NULL,NULL,NULL,?)
                """, legacyDispatch, legacyBinding, legacyFact, hash, runtime, hash);
        assertTrue(readinessViolations(jdbc) > 0, "enforced inbox cannot depend on legacy binding/generation");
        jdbc.update("DELETE FROM agent_consumer_inbox WHERE runtime_dispatch_id=?", legacyDispatch);

        assertBrokenAndRestore(jdbc,
                "UPDATE agent_command_v2_execution_lease_fact SET evidence_provenance=? WHERE lease_fact_id=?",
                "LEGACY_PRE_R2B1", lease, "R2B1_ENFORCED", lease,
                "current inbox projection cannot depend on a legacy lease");
        assertBrokenAndRestore(jdbc,
                "UPDATE agent_command_v2_ack_receipt SET execution_origin_snapshot_sha256=? WHERE runtime_dispatch_id=?",
                new byte[32], dispatch, hash, dispatch, "ACK origin must match its enforced inbox");
        assertBrokenAndRestore(jdbc,
                "UPDATE agent_command_v2_execution_lease_fact SET holder_generation_fact_id=? WHERE lease_fact_id=?",
                UUID.randomUUID().toString(), lease, fact, lease, "lease holder link must resolve");
        assertBrokenAndRestore(jdbc,
                "UPDATE agent_command_v2_execution_grant SET holder_generation_fact_id=? WHERE execution_grant_id=?",
                UUID.randomUUID().toString(), grant, fact, grant, "grant holder link must resolve");
        assertBrokenAndRestore(jdbc,
                "UPDATE agent_command_v2_execution_request SET requested_holder_generation_fact_id=? WHERE runtime_dispatch_id=?",
                UUID.randomUUID().toString(), dispatch, fact, dispatch, "request holder link must resolve");
        assertBrokenAndRestore(jdbc,
                "UPDATE agent_command_v2_execution_continuation_anchor SET execution_grant_id=? WHERE runtime_dispatch_id=?",
                UUID.randomUUID().toString(), dispatch, grant, dispatch, "anchor grant link must resolve");
        assertEquals(0, readinessViolations(jdbc));
    }

    @Test void acceptsMysqlCheckMetadataFormattingButRejectsCaseAndWhitespaceLiteralDrift() {
        String name = "chk_registration_authority_canonical";
        String expected = AgentRuntimeV2RegistrationSchemaGate.expectedChecks()
                .get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).get(name);
        String uuidPattern = "'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'";

        Fake mysqlMetadata = canonical();
        String mysqlClause = "((" + expected
                .replace("registration_authority_id REGEXP " + uuidPattern,
                        "regexp_like(`registration_authority_id`,_utf8mb4" + uuidPattern + ")")
                .replace("credential_kind='API_KEY'",
                        "`credential_kind` = _utf8mb4'API_KEY'") + "))";
        mysqlMetadata.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(mysqlClause, true));
        assertDoesNotThrow(() -> new AgentRuntimeV2RegistrationSchemaGate(mysqlMetadata)
                .afterPropertiesSet());

        Fake caseDrift = canonical();
        caseDrift.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(
                        expected.replace("'API_KEY'", "'api_key'"), true));
        assertDenied(caseDrift);

        Fake whitespaceDrift = canonical();
        whitespaceDrift.checks.get(AgentRuntimeV2RegistrationSchemaGate.AUTHORITY).put(name,
                new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(
                        expected.replace("'API_KEY'", "'API_KEY '"), true));
        assertDenied(whitespaceDrift);
    }

    @Test void propertyIsOffByDefaultWithZeroJdbcAccessAndEnabledWithoutCatalogFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ApplicationContextRunner().withBean(JdbcTemplate.class, () -> jdbc)
                .withUserConfiguration(AgentRuntimeV2RegistrationSchemaConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(AgentRuntimeV2RegistrationSchemaGate.class).isEmpty());
                    verify(jdbc, never()).execute(any(ConnectionCallback.class));
                    verify(jdbc, never()).queryForMap(anyString(), any());
                    verify(jdbc, never()).queryForList(anyString(), eq(String.class));
                });
        new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2RegistrationSchemaConfiguration.class)
                .withPropertyValues("agent.runtime-v2-registration.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    private static Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>>
            additiveColumns(String sql) {
        Set<String> targets = Set.of("agent_consumer_inbox", "agent_command_v2_ack_receipt",
                "agent_command_v2_execution_lease_fact", "agent_command_v2_execution_grant",
                "agent_command_v2_execution_continuation_anchor",
                "agent_command_v2_execution_request");
        Pattern alter = Pattern.compile("ALTER TABLE\\s+([a-z0-9_]+)\\s+(.*?);",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Pattern add = Pattern.compile(
                "ADD COLUMN\\s+([a-z0-9_]+)\\s+([a-z]+\\(\\d+\\))"
                        + "(?:\\s+COLLATE\\s+([a-z0-9_]+))?\\s+(NOT NULL|NULL)"
                        + "(?:\\s+DEFAULT\\s+'([^']+)')?"
                        + "\\s+AFTER\\s+[a-z0-9_]+",
                Pattern.CASE_INSENSITIVE);
        Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> result =
                new LinkedHashMap<>();
        Matcher blocks = alter.matcher(sql);
        while (blocks.find()) {
            String table = blocks.group(1).toLowerCase(Locale.ROOT);
            if (!targets.contains(table)) continue;
            List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns =
                    new ArrayList<>();
            Matcher additions = add.matcher(blocks.group(2));
            while (additions.find()) {
                String columnType = additions.group(2).toLowerCase(Locale.ROOT);
                String collation = additions.group(3) == null ? null
                        : additions.group(3).toLowerCase(Locale.ROOT);
                columns.add(new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                        additions.group(1).toLowerCase(Locale.ROOT),
                        columnType.substring(0, columnType.indexOf('(')), columnType,
                        "NULL".equalsIgnoreCase(additions.group(4)), additions.group(5), collation, "", ""));
            }
            if (!columns.isEmpty()) {
                assertNull(result.put(table, List.copyOf(columns)), table);
            }
        }
        assertEquals(targets, result.keySet());
        Pattern enforcedDefault = Pattern.compile(
                "ALTER TABLE\\s+([a-z0-9_]+)\\s+ALTER COLUMN\\s+evidence_provenance"
                        + "\\s+SET DEFAULT\\s+'R2B1_ENFORCED'",
                Pattern.CASE_INSENSITIVE);
        Matcher defaults = enforcedDefault.matcher(sql);
        while (defaults.find()) {
            String table = defaults.group(1).toLowerCase(Locale.ROOT);
            if (!targets.contains(table)) continue;
            List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns =
                    new ArrayList<>(result.get(table));
            replaceColumn(columns, "evidence_provenance", column ->
                    new AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition(
                            column.name(), column.dataType(), column.columnType(), column.nullable(),
                            "R2B1_ENFORCED", column.collation(), column.extra(),
                            column.generationExpression()));
            result.put(table, List.copyOf(columns));
        }
        return Map.copyOf(result);
    }

    private static void replaceColumn(
            List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns, String name,
            java.util.function.Function<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition,
                    AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> replacement) {
        for (int index = 0; index < columns.size(); index++) {
            if (name.equals(columns.get(index).name())) {
                columns.set(index, replacement.apply(columns.get(index)));
                return;
            }
        }
        fail("fixture column not found: " + name);
    }

    private static JdbcTemplate readinessJdbc() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                "jdbc:h2:mem:r2b1_readiness_" + UUID.randomUUID()
                        + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "", true));
        jdbc.execute("""
                CREATE TABLE agent_runtime_v2_binding(
                  contract_version SMALLINT,evidence_provenance VARCHAR(24),status VARCHAR(20),
                  runtime_binding_id VARCHAR(36),binding_generation BIGINT,runtime_instance_id VARCHAR(36),
                  current_registration_receipt_id VARCHAR(36),current_registration_receipt_sha256 VARBINARY(32))
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime_v2_binding_generation_fact(
                  evidence_provenance VARCHAR(24),binding_generation_fact_id VARCHAR(36),
                  runtime_binding_id VARCHAR(36),binding_generation BIGINT,runtime_instance_id VARCHAR(36),
                  event_type VARCHAR(20),registration_receipt_id VARCHAR(36),
                  registration_receipt_sha256 VARBINARY(32),generation_snapshot_sha256 VARBINARY(32))
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime_v2_registration_receipt(
                  registration_receipt_id VARCHAR(36),receipt_wire_sha256 VARBINARY(32),
                  binding_generation_fact_id VARCHAR(36),runtime_binding_id VARCHAR(36),binding_generation BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_consumer_inbox(
                  evidence_provenance VARCHAR(24),protocol_version SMALLINT,runtime_dispatch_id VARCHAR(36),
                  runtime_binding_id VARCHAR(36),dispatch_generation_fact_id VARCHAR(36),
                  dispatch_generation_snapshot_sha256 VARBINARY(32),runtime_binding_generation BIGINT,
                  dispatch_runtime_instance_id VARCHAR(36),execution_current_lease_fact_id VARCHAR(36),
                  execution_holder_binding_id VARCHAR(36),execution_holder_binding_generation BIGINT,
                  execution_holder_runtime_instance_id VARCHAR(36),execution_origin_snapshot_sha256 VARBINARY(32))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_ack_receipt(
                  evidence_provenance VARCHAR(24),contract_version SMALLINT,runtime_dispatch_id VARCHAR(36),
                  execution_origin_snapshot_sha256 VARBINARY(32),ack_holder_generation_fact_id VARCHAR(36),
                  ack_holder_generation_snapshot_sha256 VARBINARY(32),runtime_binding_id VARCHAR(36),
                  ack_holder_binding_generation BIGINT,runtime_instance_id VARCHAR(36))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_lease_fact(
                  evidence_provenance VARCHAR(24),lease_fact_id VARCHAR(36),runtime_dispatch_id VARCHAR(36),
                  runtime_binding_id VARCHAR(36),holder_binding_generation BIGINT,runtime_instance_id VARCHAR(36),
                  execution_origin_snapshot_sha256 VARBINARY(32),holder_generation_fact_id VARCHAR(36),
                  holder_generation_snapshot_sha256 VARBINARY(32))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_grant(
                  evidence_provenance VARCHAR(24),execution_grant_id VARCHAR(36),runtime_dispatch_id VARCHAR(36),
                  runtime_binding_id VARCHAR(36),holder_binding_generation BIGINT,runtime_instance_id VARCHAR(36),
                  execution_origin_snapshot_sha256 VARBINARY(32),holder_generation_fact_id VARCHAR(36),
                  holder_generation_snapshot_sha256 VARBINARY(32))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_request(
                  evidence_provenance VARCHAR(24),runtime_dispatch_id VARCHAR(36),runtime_binding_id VARCHAR(36),
                  requested_holder_binding_generation BIGINT,runtime_instance_id VARCHAR(36),
                  execution_origin_snapshot_sha256 VARBINARY(32),requested_holder_generation_fact_id VARCHAR(36),
                  requested_holder_generation_snapshot_sha256 VARBINARY(32))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_continuation_anchor(
                  evidence_provenance VARCHAR(24),runtime_dispatch_id VARCHAR(36),execution_grant_id VARCHAR(36),
                  execution_origin_snapshot_sha256 VARBINARY(32),holder_generation_fact_id VARCHAR(36),
                  holder_generation_snapshot_sha256 VARBINARY(32),holder_binding_generation BIGINT)
                """);
        return jdbc;
    }

    private static void seedLegacyEvidenceHistory(JdbcTemplate jdbc, String binding, String fact) {
        jdbc.update("INSERT INTO agent_runtime_v2_binding VALUES(2,'LEGACY_PRE_R2B1','REVOKED',?,0,?,NULL,NULL)",
                binding, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO agent_runtime_v2_binding_generation_fact VALUES('LEGACY_PRE_R2B1',?,?,0,?,'CONNECTED',NULL,NULL,NULL)",
                fact, binding, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO agent_consumer_inbox(evidence_provenance) VALUES('LEGACY_PRE_R2B1')");
        jdbc.update("INSERT INTO agent_command_v2_ack_receipt(evidence_provenance) VALUES('LEGACY_PRE_R2B1')");
        jdbc.update("INSERT INTO agent_command_v2_execution_lease_fact(evidence_provenance) VALUES('LEGACY_PRE_R2B1')");
        jdbc.update("INSERT INTO agent_command_v2_execution_grant(evidence_provenance) VALUES('LEGACY_PRE_R2B1')");
        jdbc.update("INSERT INTO agent_command_v2_execution_request(evidence_provenance) VALUES('LEGACY_PRE_R2B1')");
        jdbc.update("INSERT INTO agent_command_v2_execution_continuation_anchor(evidence_provenance) VALUES('LEGACY_PRE_R2B1')");
    }

    private static void seedCompleteEnforcedEvidenceGraph(JdbcTemplate jdbc, String binding,
            String fact, String receipt, String runtime, String dispatch, String lease,
            String grant, byte[] hash) {
        jdbc.update("INSERT INTO agent_runtime_v2_binding VALUES(2,'R2B1_ENFORCED','ACTIVE',?,0,?,?,?)",
                binding, runtime, receipt, hash);
        jdbc.update("INSERT INTO agent_runtime_v2_binding_generation_fact VALUES('R2B1_ENFORCED',?,?,0,?,'CONNECTED',?,?,?)",
                fact, binding, runtime, receipt, hash, hash);
        jdbc.update("INSERT INTO agent_runtime_v2_registration_receipt VALUES(?,?,?,?,?)",
                receipt, hash, fact, binding, 0L);
        jdbc.update("INSERT INTO agent_consumer_inbox VALUES('R2B1_ENFORCED',2,?,?,?,?,0,?,?,?,?,?,?)",
                dispatch, binding, fact, hash, runtime, lease, binding, 0L, runtime, hash);
        jdbc.update("INSERT INTO agent_command_v2_ack_receipt VALUES('R2B1_ENFORCED',2,?,?,?,?,?,?,?)",
                dispatch, hash, fact, hash, binding, 0L, runtime);
        jdbc.update("INSERT INTO agent_command_v2_execution_lease_fact VALUES('R2B1_ENFORCED',?,?,?,?,?,?,?,?)",
                lease, dispatch, binding, 0L, runtime, hash, fact, hash);
        jdbc.update("INSERT INTO agent_command_v2_execution_grant VALUES('R2B1_ENFORCED',?,?,?,?,?,?,?,?)",
                grant, dispatch, binding, 0L, runtime, hash, fact, hash);
        jdbc.update("INSERT INTO agent_command_v2_execution_request VALUES('R2B1_ENFORCED',?,?,?,?,?,?,?)",
                dispatch, binding, 0L, runtime, hash, fact, hash);
        jdbc.update("INSERT INTO agent_command_v2_execution_continuation_anchor VALUES('R2B1_ENFORCED',?,?,?,?,?,?)",
                dispatch, grant, hash, fact, hash, 0L);
    }

    private static void assertBrokenAndRestore(JdbcTemplate jdbc, String sql,
            Object brokenValue, String brokenKey, Object restoredValue, String restoredKey,
            String message) {
        assertEquals(1, jdbc.update(sql, brokenValue, brokenKey));
        assertTrue(readinessViolations(jdbc) > 0, message);
        assertEquals(1, jdbc.update(sql, restoredValue, restoredKey));
        assertEquals(0, readinessViolations(jdbc), "fixture failed to restore after: " + message);
    }

    private static long readinessViolations(JdbcTemplate jdbc) {
        return new AgentRuntimeV2RegistrationSchemaGate.JdbcCatalog(jdbc)
                .activationReadinessViolationCount();
    }

    private static void replaceColumn(Fake fake, String table, String name,
            java.util.function.Function<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition,
                    AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> replacement) {
        List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns = fake.columns.get(table);
        for (int index = 0; index < columns.size(); index++) {
            if (name.equals(columns.get(index).name())) {
                columns.set(index, replacement.apply(columns.get(index)));
                return;
            }
        }
        fail("fixture column not found: " + name);
    }
    private static void assertDenied(Fake fake) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentRuntimeV2RegistrationSchemaGate(fake).afterPropertiesSet());
        assertTrue(failure.getMessage().startsWith("Agent runtime v2 registration schema unavailable:"));
    }
    private static Fake canonical() {
        Fake fake = new Fake();
        fake.tables.addAll(AgentRuntimeV2RegistrationSchemaGate.TABLES.stream().sorted().toList());
        for (String table : fake.tables) {
            fake.tablesByName.put(table, new AgentRuntimeV2RegistrationSchemaGate.TableDefinition("InnoDB", "utf8mb4_0900_bin"));
            fake.columns.put(table, new ArrayList<>(AgentRuntimeV2RegistrationSchemaGate.expectedColumns().get(table)));
            fake.indexes.put(table, new LinkedHashMap<>(AgentRuntimeV2RegistrationSchemaGate.expectedIndexes().get(table)));
            Map<String, AgentRuntimeV2RegistrationSchemaGate.CheckDefinition> checks = new LinkedHashMap<>();
            AgentRuntimeV2RegistrationSchemaGate.expectedChecks().get(table).forEach((name, clause) ->
                    checks.put(name, new AgentRuntimeV2RegistrationSchemaGate.CheckDefinition(clause, true)));
            fake.checks.put(table, checks);
            fake.foreignKeys.put(table, 0);
        }
        return fake;
    }

    private static final class Fake implements AgentRuntimeV2RegistrationSchemaGate.Catalog {
        String product = "MySQL 8.4"; int reads; long readinessViolations;
        final List<String> tables = new ArrayList<>();
        final Map<String, AgentRuntimeV2RegistrationSchemaGate.TableDefinition> tablesByName = new HashMap<>();
        final Map<String, List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition>> columns = new HashMap<>();
        final Map<String, Map<String, AgentRuntimeV2RegistrationSchemaGate.IndexDefinition>> indexes = new HashMap<>();
        final Map<String, Integer> foreignKeys = new HashMap<>();
        final Map<String, Map<String, AgentRuntimeV2RegistrationSchemaGate.CheckDefinition>> checks = new HashMap<>();
        public String databaseProduct() { reads++; return product; }
        public List<String> presentTables() { reads++; return List.copyOf(tables); }
        public AgentRuntimeV2RegistrationSchemaGate.TableDefinition table(String table) { reads++; return tablesByName.get(table); }
        public List<AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition> columns(String table) { reads++; return List.copyOf(columns.get(table)); }
        public Map<String, AgentRuntimeV2RegistrationSchemaGate.IndexDefinition> indexes(String table) { reads++; return Map.copyOf(indexes.get(table)); }
        public int participatingForeignKeyCount(String table) { reads++; return foreignKeys.getOrDefault(table, -1); }
        public Map<String, AgentRuntimeV2RegistrationSchemaGate.CheckDefinition> checks(String table) { reads++; return Map.copyOf(checks.get(table)); }
        public long activationReadinessViolationCount() { reads++; return readinessViolations; }
    }
}
