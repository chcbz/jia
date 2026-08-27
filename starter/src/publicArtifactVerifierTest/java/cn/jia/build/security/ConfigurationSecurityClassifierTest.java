package cn.jia.build.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationSecurityClassifierTest {
    @Test
    void yaml01PendingApiKeyOwnsIndentedScalar() throws Exception {
        assertYamlViolation("api-key:\n  live-secret\n");
    }

    @Test
    void yaml02PendingApiKeyOwnsIndentedSequence() throws Exception {
        assertYamlViolation("api-key:\n  - ${PUBLIC_API_KEY}\n");
    }

    @Test
    void yaml03PendingApiKeyOwnsIndentedMappingAndTraversesIt() throws Exception {
        assertEquals(List.of("api-key", "nested-api-key"), yaml(
                "api-key:\n  nested-api-key: live-secret\n"));
    }

    @Test
    void yaml04FlowCollectionsAndCompactSequenceMappingsMatchBlockSemantics() throws Exception {
        String yaml = "---\n{root-api-key: live-secret}\n"
                + "---\nproviders: [{api-key: live-secret}, [safe, {nested_api_key: other-secret}]]\n"
                + "more:\n  - api-key: live-secret\n";
        assertEquals(List.of("root-api-key", "api-key", "nested-api-key", "api-key"), yaml(yaml));
    }

    @Test
    void yaml05QuotedEscapedKeysAreDecodedBeforeClassification() throws Exception {
        assertEquals(List.of("api-key", "api-key"), yaml(
                "\"api\\u002dkey\": live-secret\n'api-key': another-secret\n"));
    }

    @Test
    void yaml06EveryDocumentIsTraversed() throws Exception {
        assertEquals(List.of("api-key"), yaml(
                "---\napi-key: ${PUBLIC_API_KEY}\n...\n---\napi-key: live-secret\n...\n"));
    }

    @Test
    void yaml07CommentsAreQuoteAware() throws Exception {
        assertEquals(List.of(), yaml("ordinary: \"value#inside\" # comment\napi-key: ${PUBLIC_API_KEY}\n"));
        assertEquals(List.of("api-key"), yaml("api-key: \"live#secret\" # comment\n"));
    }

    @Test
    void yaml08ImplicitNullWithoutChildIsAllowed() throws Exception {
        assertEquals(List.of(), yaml("api-key:\nsibling: ordinary\n"));
    }

    @Test
    void yaml09BlockScalarBoundToApiKeyIsUnsafeAndBodyIsRedacted() throws Exception {
        assertEquals(List.of("api-key", "later-api-key"), yaml(
                "api-key: |-\n  adversarial-secret-canary\nlater-api-key: live-secret\n"));
    }

    @Test
    void yaml10AliasesTagsMergeAndDirectivesAreRejected() {
        for (String yaml : List.of(
                "api-key: &secret live\n",
                "api-key: *secret\n",
                "api-key: !vault live\n",
                "<<: *defaults\n",
                "%YAML 1.2\n---\napi-key: live\n")) {
            assertParseFailure("application.yaml", yaml.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void yaml11MalformedUtf8EscapeIndentAndFlowFailClosed() {
        assertParseFailure("application.yaml", new byte[]{(byte) 0xc3, 0x28});
        assertParseFailure("application.yaml", "\"api\\uZZZZkey\": value\n".getBytes(StandardCharsets.UTF_8));
        assertParseFailure("application.yaml", "parent:\n   child: value\n  sibling: value\n".getBytes(StandardCharsets.UTF_8));
        assertParseFailure("application.yaml", "provider: {api-key: value\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void prop01UnsafeDuplicateCannotBeOverwrittenByLaterSafeDuplicate() throws Exception {
        String properties = "api-key=live-secret\n"
                + "api-key=${PUBLIC_API_KEY}\n"
                + "escaped\\-api\\-key=other-secret\n"
                + "continued-api-key=continued-\\\n secret\n";
        assertEquals(List.of("api-key", "escaped-api-key", "continued-api-key"),
                keys(ConfigurationSecurityClassifier.classify(
                        "application.properties", properties.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void allowedValuePolicyIsExactAndEmbeddedDefaultsAreUnsafe() throws Exception {
        String properties = "a-api-key=\n"
                + "b-api-key=ENC(ciphertext)\n"
                + "c-api-key=${PUBLIC_API_KEY}\n"
                + "d-api-key=${PUBLIC_API_KEY:}\n"
                + "e-api-key=dummy-value\n"
                + "f-api-key=${PUBLIC_API_KEY:embedded}\n";
        assertEquals(List.of("f-api-key"), keys(ConfigurationSecurityClassifier.classify(
                "application.properties", properties.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void diagnosticsNeverContainScalarCanaries() throws Exception {
        List<String> keys = yaml("provider: {api-key: adversarial-secret-canary}\n");
        assertEquals(List.of("api-key"), keys);
        assertTrue(keys.stream().noneMatch(value -> value.contains("canary")));
    }

    private static void assertYamlViolation(String yaml) throws Exception {
        assertEquals(List.of("api-key"), yaml(yaml));
    }

    private static List<String> yaml(String yaml) throws Exception {
        return keys(ConfigurationSecurityClassifier.classify(
                "application.yaml", yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> keys(List<ConfigurationSecurityClassifier.Match> matches) {
        for (ConfigurationSecurityClassifier.Match match : matches) {
            assertEquals(PublicArtifactVerifier.FailureCode.API_KEY_VIOLATION, match.code());
        }
        return matches.stream().map(ConfigurationSecurityClassifier.Match::key).toList();
    }

    private static void assertParseFailure(String name, byte[] content) {
        try {
            ConfigurationSecurityClassifier.classify(name, content);
            fail("configuration unexpectedly parsed");
        } catch (PublicArtifactVerifier.VerificationException failure) {
            assertEquals(PublicArtifactVerifier.FailureCode.CONFIGURATION_PARSE_ERROR, failure.code());
        }
    }
}
