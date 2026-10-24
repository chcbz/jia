package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Pure source/catalog normalization tests. No fixture, database or native IO. */
class AgentMysqlCheckCanonicalizerTest {
    // Exact schema-only native inventory value, not a guessed JDBC string.
    static final String NATIVE_RELAY = "(((`protocol_version` is null) and (`tenant_id` = _utf8mb4\\'0\\') and (`data_domain_id` is null) and (`execution_pool_id` is null) and (`admission_epoch` is null) and (`producer_deployment_id` is null) and (`module_id` is null) and (`migration_epoch` is null) and (`claimed_deployment_id` is null) and (`claimed_worker_generation` is null)) or ((`protocol_version` = 2) and (length(`data_domain_id`) between 1 and 64) and regexp_like(`execution_pool_id`,_utf8mb4\\'^[a-z0-9-]{1,100}$\\') and (`admission_epoch` >= 0) and (length(`producer_deployment_id`) between 1 and 100) and regexp_like(`module_id`,_utf8mb4\\'^[a-z][a-z0-9_-]{0,63}$\\') and (`migration_epoch` >= 0) and (((`claimed_deployment_id` is null) and (`claimed_worker_generation` is null)) or ((length(`claimed_deployment_id`) between 1 and 100) and (`claimed_worker_generation` >= 0)))))";
    static final String RELAY_SQL = "agent/jia-agent-mapper/src/main/resources/db/agent-outbox-relay-v2-manual-20260925.sql";
    static final String CONSUMER_SQL = "agent/jia-agent-mapper/src/main/resources/db/agent-command-consumer-v2-manual-20260925.sql";
    static final String PERSISTENCE_SQL = "agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-persistence-manual-20260930.sql";

    static String source(String relative) throws Exception {
        return Files.readString(Path.of(System.getProperty("user.dir"), "..", "..", relative).normalize());
    }
    static Map<String,String> checks(String sql) {
        Map<String,String> result = new LinkedHashMap<>();
        var matcher = Pattern.compile("CONSTRAINT\\s+([a-z0-9_]+)\\s+CHECK\\s*\\(").matcher(sql);
        while (matcher.find()) {
            int start = matcher.end(), depth = 1, i = start;
            boolean quoted = false;
            for (; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '\'') {
                    if (quoted && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') i++;
                    else quoted = !quoted;
                } else if (!quoted) {
                    if (c == '(') depth++;
                    else if (c == ')' && --depth == 0) break;
                }
            }
            assertEquals(0, depth); assertFalse(quoted);
            result.put(matcher.group(1), sql.substring(start, i).trim());
        }
        assertFalse(result.isEmpty()); return result;
    }
    static String metadata(String source) {
        // Modeling ONLY the literal-delimiter representation seen in the saved
        // catalog. This is not an SQL backslash decoder or native/JDBC proof.
        assertFalse(source.contains("''"));
        return source.replaceAll("'([^']*)'", "_utf8mb4\\\\'$1\\\\'");
    }
    private static String key(String value) { return AgentMysqlCheckCanonicalizer.canonicalCheck(value); }
    private static void denied(String expected, String actual) {
        String canonical = key(expected);
        try { assertNotEquals(canonical, key(actual), actual); }
        catch (IllegalArgumentException malformed) { /* Strict grammar rejection is fail closed. */ }
    }

    @Test void exactRetainedNativeRelayAndIsolatedRepresentationsAgree() throws Exception {
        assertEquals(846, NATIVE_RELAY.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("b67edeaf0b8701828ab0697b4b151f05149aa8fa57e657aab7995f5c144ff097",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(NATIVE_RELAY.getBytes(StandardCharsets.UTF_8))));
        String source = checks(source(RELAY_SQL)).get("chk_delivery_relay_v2_scope");
        assertEquals(key(source), key(NATIVE_RELAY));
        assertEquals(key(source), key(source.replace("OCTET_LENGTH(", "LENGTH(")));
        assertEquals(key(source), key(metadata(source)));
        assertEquals(key(source), key(NATIVE_RELAY.replace("\\'", "'")));
    }

    @Test void aliasesAreFunctionTokensNeverLiteralOrIdentifierSubstrings() {
        assertEquals(key("length(x) BETWEEN 1 AND 64"), key("OCTET_LENGTH (`x`) BETWEEN 1 AND 64"));
        denied("octet_length(x)=1", "CHAR_LENGTH(x)=1");
        denied("octet_length(x)=1", "CHARACTER_LENGTH(x)=1");
        denied("octet_length(x)=1", "my_length(x)=1");
        assertNotEquals(key("length_field=1"), key("octet_length_field=1"));
        assertNotEquals(key("x='LENGTH(x)'"), key("x='OCTET_LENGTH(x)'"));
        assertThrows(IllegalArgumentException.class, () -> key("`length`(x)=1"));
    }

    @Test void regexpSupportsExactTwoArgumentsAndSyntaxWhitespaceOnly() {
        String expected = "execution_pool_id REGEXP '^[a-z0-9-]{1,100}$'";
        assertEquals(key(expected), key("REGEXP_LIKE ( `execution_pool_id` , _utf8mb4 '^[a-z0-9-]{1,100}$' )"));
        denied(expected, "REGEXP_LIKE(execution_pool_id,'^[a-z0-9-]{1,100}$','c')");
        denied(expected, "REGEXP_LIKE(execution_pool_id,'^[a-z0-9-]{1,100}$','i')");
        denied(expected, "REGEXP_LIKE(CAST(execution_pool_id AS BINARY),'^[a-z0-9-]{1,100}$')");
        denied(expected, "CAST(execution_pool_id AS BINARY) REGEXP '^[a-z0-9-]{1,100}$'");
        denied(expected, "execution_pool_id COLLATE utf8mb4_general_ci REGEXP '^[a-z0-9-]{1,100}$'");
        denied(expected, "REGEXP_LIKE(execution_pool_id,concat('^','[a-z0-9-]{1,100}$'))");
    }

    @Test void existingExplicitCaseSensitiveThirdArgumentIsPreservedNotAliased() {
        String explicit = "REGEXP_LIKE(id,'^[a-z]+$','c')";
        assertEquals(key(explicit), key("regexp_like(`id`, _utf8mb4'^[a-z]+$', _utf8mb4'c')"));
        denied(explicit, "id REGEXP '^[a-z]+$'");
        denied(explicit, "REGEXP_LIKE(id,'^[a-z]+$','i')");
        assertNotEquals(key("NOT " + explicit), key(explicit));
    }

    @Test void literalsKeepCaseWhitespaceAndTextThatLooksLikeSyntax() {
        denied("x REGEXP '^[a-z0-9-]{1,100}$'", "x REGEXP '^[A-Z0-9-]{1,100}$'");
        denied("status='EXPIRED'", "status='expired'");
        denied("status='A  B'", "status='A B'");
        denied("status='A B'", "status='A\tB'");
        assertEquals("x = 'LENGTH(x) _utf8mb4 `Name` AND OR'",
                AgentMysqlCheckCanonicalizer.sql("x='LENGTH(x) _utf8mb4 `Name` AND OR'"));
        assertEquals(key("x='A''B'"), key("`x` = _utf8mb4'A''B'"));
    }

    @Test void quotedIdentifiersAndIntroducersHaveWholeTokenBoundaries() {
        assertEquals(key("execution_pool_id='0'"), key("`execution_pool_id`=_utf8mb4'0'"));
        denied("execution_pool_id='0'", "`EXECUTION_POOL_ID`='0'");
        for (String invalid : List.of("execution`_pool`_id='0'", "`execution_pool_id`tail='0'",
                "execution_pool_id=_utf8mb4_fake'0'", "execution_pool_id=_utf8mb4_utf8mb4'0'",
                "execution_pool_id=_latin1'0'", "execution_pool_id=_utf8mb4", "`execution_pool_id='0'"))
            assertThrows(IllegalArgumentException.class, () -> key(invalid), invalid);
        denied("execution_pool_id='0'", "execution_pool_utf8mb4_id='0'");
    }

    @Test void malformedEscapingNeverBecomesCanonical() {
        for (String invalid : List.of("x='unterminated", "x=_utf8mb4\\'unterminated",
                "x=\\'0\\'", "x=_utf8mb4\\\\'0\\\\'", "x=_utf8mb4\\'a\\\\b\\'",
                "x=_utf8mb4\\'a'b\\'", "x=_utf8mb4\\'0\\' AND y='0'",
                "x=_utf8mb4'0' AND y=_utf8mb4\\'0\\'", "x='a\\'b'", "x='0''", "x=_utf8mb4\\'0'"))
            assertThrows(IllegalArgumentException.class, () -> key(invalid), invalid);
    }

    @Test void grammarConsumesEverythingAndValidatesGroupingAndBetween() {
        for (String invalid : List.of("", " ", "()", "(x=1", "x=1)", "x=1 garbage",
                "x=1;", "x=1 -- comment", "x=1/*comment*/", "x=1#comment", "x=1 AND",
                "OR x=1", "x BETWEEN 1", "x BETWEEN 1 OR 64", "x IN ()", "x=1,,", "x=1=1",
                "unknown_function(x)=1", "x! =1", "((x=1) AND ())", "length(x,y)=1"))
            assertThrows(IllegalArgumentException.class, () -> key(invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> key(null));
        assertEquals(key("length(x) BETWEEN 1 AND 64 AND (y=1 OR y=2)"),
                key("((octet_length(x) BETWEEN 1 AND 64) AND ((y=1) OR (y=2)))"));
        denied("x=1 OR y=2", "y=2 OR x=1");
        denied("x=1 AND y=2", "y=2 AND x=1");
        denied("x=1 AND (y=2 AND z=3)", "(x=1 AND y=2) AND z=3");
        denied("x=1 AND y=2", "x=1 AND y=2 OR 1=1");
        denied("x IS NOT NULL AND x=2", "x=2");
        denied("x NOT IN ('A','B')", "x IN ('A','B')");
        assertThrows(IllegalArgumentException.class, () -> key("(".repeat(129)+"x=1"+")".repeat(129)));
    }

    @Test void generatedMetadataEmptyPathAndSupportedCaseAreSeparateFromChecks() {
        assertEquals("", AgentMysqlCheckCanonicalizer.generatedExpression(null));
        assertEquals("", AgentMysqlCheckCanonicalizer.generatedExpression(" "));
        String source = "CASE WHEN status='OPEN' THEN CONCAT(tenant_id,':',module_id) ELSE NULL END";
        assertEquals(AgentMysqlCheckCanonicalizer.generatedExpression(source),
                AgentMysqlCheckCanonicalizer.generatedExpression("(case when (`status`=_utf8mb4'OPEN') then concat(`tenant_id`,_utf8mb4':',`module_id`) else null end)"));
        assertThrows(IllegalArgumentException.class, () -> key(source));
        assertThrows(IllegalArgumentException.class, () -> AgentMysqlCheckCanonicalizer.generatedExpression("case when x=1 then x end"));
        assertThrows(IllegalArgumentException.class, () -> AgentMysqlCheckCanonicalizer.generatedExpression("x"));
        assertNotEquals(AgentMysqlCheckCanonicalizer.generatedExpression(source),
                AgentMysqlCheckCanonicalizer.generatedExpression(source.replace("'OPEN'", "'open'")));
    }

    @Test void scalarOperationGroupingIsRetainedAndRoundTrips() {
        String left = "(a+b)+c=6", right = "a+(b+c)=6";
        assertNotEquals(key(left), key(right));
        assertEquals(key(left), key("a+b+c=6"));
        assertNotEquals(key("a-(b-c)=6"), key("(a-b)-c=6"));
        for (String expression : List.of(left, right, "a-(b-c)=6", "(a-b)-c=6", "length(a)+length(b)+length(c)<=100"))
            assertEquals(key(expression), key(AgentMysqlCheckCanonicalizer.sql(expression)));
        assertThrows(IllegalArgumentException.class, () -> key("not ".repeat(129) + "x=1"));
    }

    @Test void retainedGeneratedFieldsAndIndependentPlainMetadataHaveBoundedKeys() throws Exception {
        try (var stream = getClass().getResourceAsStream("runtime-v2-generated-columns-native-20261008.tsv")) {
            assertNotNull(stream);
            String fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(fixture.contains("996294187783dec5b98f5b096b5241424d58d585c69158615cd137dfa10ec287"));
            assertTrue(fixture.contains("NOT proof of a separately captured JDBC representation"));
            var columns = AgentRuntimeV2RegistrationSchemaGate.expectedColumns().get("agent_runtime_v2_binding");
            int count = 0;
            for (String line : fixture.lines().filter(s -> !s.startsWith("#") && !s.isBlank()).toList()) {
                String[] fields = line.split("\t", -1); assertEquals(3, fields.length);
                assertEquals(fields[1], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(fields[2].getBytes(StandardCharsets.UTF_8))));
                String raw = columns.stream().filter(c -> c.name().equals(fields[0])).findFirst().orElseThrow().generationExpression();
                String key = AgentMysqlCheckCanonicalizer.generatedExpression(raw);
                assertEquals(key, AgentMysqlCheckCanonicalizer.generatedExpression(fields[2]));
                // This is a separate synthetic JDBC-like case, not an assertion
                // that the saved CLI representation is JDBC's literal value.
                String plain = "(" + raw.replace("octet_length(", "length(") + ")";
                assertNotEquals(fields[2], plain);
                assertEquals(key, AgentMysqlCheckCanonicalizer.generatedExpression(plain));
                assertThrows(IllegalArgumentException.class, () -> AgentMysqlCheckCanonicalizer.generatedExpression(key));
                count++;
            }
            assertEquals(2, count);
        }
    }

    @Test void generatedGrammarRejectsMeaningChangesAndUnsupportedForms() {
        String raw = "case when ((`status` = _utf8mb4'OPEN') and (`subject` is not null)) then sha2(concat(lpad(octet_length(`tenant_id`),3,_utf8mb4'0'),_utf8mb4':',`tenant_id`,`module_id`),256) else NULL end";
        String expected = AgentMysqlCheckCanonicalizer.generatedExpression(raw);
        for (String changed : List.of(raw.replace("'OPEN'", "'open'"), raw.replace("`status`", "`STATUS`"),
                raw.replace("octet_length", "char_length"), raw.replace("sha2", "hex"),
                raw.replace(",256)", ",224)"), raw.replace(",3,", ",4,"),
                raw.replace("`tenant_id`,`module_id`", "`module_id`,`tenant_id`"),
                raw.replace("`subject` is not null", "`subject` is null"),
                raw.replace(" and (`subject` is not null)", ""),
                raw.replace("else NULL", "else 'OPEN'"), raw.replace("then sha2", "then unknown"),
                raw + ";", raw.substring(0, raw.length()-4), "generated:" + raw,
                "case when x=1 then x end", "if(x=1,1,null)", "x")) {
            assertNotEquals(raw, changed);
            try { assertNotEquals(expected, AgentMysqlCheckCanonicalizer.generatedExpression(changed)); }
            catch (IllegalArgumentException malformed) { /* Bounded parser rejects fail closed. */ }
            assertFalse(AgentRuntimeV2PersistenceSchemaGate.sameGeneratedExpression(raw, changed));
        }
        assertFalse(AgentRuntimeV2PersistenceSchemaGate.sameGeneratedExpression("if(x=1,1,null)", "if(x=1,1,null)"));
    }

    @Test void unparenthesizedNestedCaseHasAnExplicitScalarDepthBound() {
        String supported = "case when x=1 then ".repeat(8) + "'OPEN'" + " else null end".repeat(8);
        String generated = AgentMysqlCheckCanonicalizer.generatedExpression(supported);
        assertEquals(generated, AgentMysqlCheckCanonicalizer.generatedExpression(
                generated.substring("generated:".length())));
        assertEquals(generated, AgentMysqlCheckCanonicalizer.generatedExpression(metadata(supported)));
        // No parentheses; well below lexer length/token limits. The scalar
        // recursion bound must throw IllegalArgumentException, not overflow.
        String deep = "case when x=1 then ".repeat(2048) + "'OPEN'" + " else null end".repeat(2048);
        assertThrows(IllegalArgumentException.class,
                () -> AgentMysqlCheckCanonicalizer.generatedExpression(deep));
        assertThrows(IllegalArgumentException.class,
                () -> AgentMysqlCheckCanonicalizer.canonicalCheck("x=" + deep));
    }

    @Test void frozenDownstreamSourceConstraintsKeepKnownExpressionsAndRepresentations() throws Exception {
        int count = 0;
        for (String relative : List.of(RELAY_SQL, CONSUMER_SQL, PERSISTENCE_SQL,
                "agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-registration-manual-20260930.sql",
                "agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-registration-activation-manual-20260930.sql",
                "agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-challenge-decision-manual-20261001.sql",
                "agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-dispatch-manual-20261001.sql",
                "agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-execution-manual-20261001.sql",
                "agent/jia-agent-service/src/main/resources/db/agent-runtime-v2-effects-manual-20261003.sql",
                "oauth/jia-oauth-mapper/src/main/resources/db/runtime-v2-drain-authority-manual-20261001.sql")) {
            for (var entry : checks(source(relative)).entrySet()) {
                String expected = entry.getValue();
                assertDoesNotThrow(() -> key(expected), relative + ":" + entry.getKey());
                assertEquals(key(expected), key(expected.replace("OCTET_LENGTH(", "LENGTH(")), entry.getKey());
                assertEquals(key(expected), key(metadata(expected)), entry.getKey());
                count++;
            }
        }
        assertEquals(60, count);
    }

    @Test void sourceRequiredFlagsAndBinaryCastsCannotDriftToAliasShapes() throws Exception {
        String dispatch = source("agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v2-dispatch-manual-20261001.sql");
        int flags = 0;
        for (String check : checks(dispatch).values()) {
            if (!check.contains(",'c')")) continue;
            denied(check, check.replace(",'c')", ",'i')"));
            denied(check, check.replace(",'c')", ")"));
            flags++;
        }
        assertTrue(flags > 0, "unchanged dispatch source explicitly requires case-sensitive flags");
        int casts = 0;
        for (String check : checks(source(PERSISTENCE_SQL)).values()) {
            if (!check.contains("CAST(")) continue;
            denied(check, check.replaceAll("CAST\\(([a-z_]+) AS BINARY\\)", "$1"));
            casts++;
        }
        assertEquals(2, casts, "unchanged persistence source has two binary-cast CHECKs");
    }
}
