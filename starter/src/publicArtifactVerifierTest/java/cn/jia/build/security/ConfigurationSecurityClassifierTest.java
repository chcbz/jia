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
    void yaml12TokenEventLimitAcceptsBoundaryAndRejectsNextCollectionEnd() throws Exception {
        // Flow sequence accounting is two collection events plus scalar+entry per item.
        int boundaryItems = (BoundedYamlParser.MAX_TOKENS - 2) / 2;
        byte[] atLimit = flowSequence(boundaryItems).getBytes(StandardCharsets.UTF_8);
        assertEquals(1, BoundedYamlParser.parse(atLimit).size());
        assertParseFailure("application.yaml",
                flowSequence(boundaryItems + 1).getBytes(StandardCharsets.UTF_8));
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
    void propertiesAcceptBenignLatin1AndUtf8WithoutWeakeningSecretDetection() throws Exception {
        for (var charset : List.of(StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8)) {
            assertEquals(List.of(), keys(ConfigurationSecurityClassifier.classify(
                    "messages.properties", "message=Español café\napi-key=${PUBLIC_API_KEY}\n".getBytes(charset))));
            String unsafe = "message=café\napi-key=encoding-secret-canary\n"
                    + "api-key=${PUBLIC_API_KEY}\n"
                    + "escaped-api\\u002dkey=encoding-secret-canary\n"
                    + "continued-api-key=encoding-\\\n secret-canary\n";
            List<ConfigurationSecurityClassifier.Match> matches = ConfigurationSecurityClassifier.classify(
                    "messages.properties", unsafe.getBytes(charset));
            assertEquals(List.of("api-key", "escaped-api-key", "continued-api-key"), keys(matches));
            assertTrue(matches.stream().noneMatch(value -> value.toString().contains("secret-canary")));
        }
    }

    @Test
    void unmarkedUtf8InterpretationCannotBeLostToLatin1Fallback() throws Exception {
        // Kelvin sign lowercases to ASCII k under the existing canonical-key rule.
        assertEquals(List.of("api-key"), keys(ConfigurationSecurityClassifier.classify(
                "messages.properties", "api-\u212aey=utf8-secret-canary\n".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void propertiesBomKeepsStrippedUtf8SemanticsAndRejectsMalformedMarkedInput() throws Exception {
        assertEquals(List.of(), keys(ConfigurationSecurityClassifier.classify("bom.properties",
                "\ufeffapi-key=${PUBLIC_API_KEY}\nmessage=中文\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals(List.of("api-key"), keys(ConfigurationSecurityClassifier.classify("bom.properties",
                "\ufeffapi-key=bom-secret-canary\n".getBytes(StandardCharsets.UTF_8))));
        assertParseFailure("bom.properties",
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xc3, 0x28});
    }

    @Test
    void propertiesEncodingCompatibilityStillRejectsMalformedEscapesAndBounds() {
        for (var charset : List.of(StandardCharsets.ISO_8859_1, StandardCharsets.UTF_8)) {
            assertParseFailure("messages.properties",
                    "message=café\nbad=\\uZZZZ-secret-canary\n".getBytes(charset));
        }
        assertParseFailure("messages.properties", new byte[(1 << 20) + 1]);
        assertParseFailure("messages.properties", "\n".repeat(10_000).getBytes(StandardCharsets.UTF_8));
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

    private static String flowSequence(int items) {
        StringBuilder yaml = new StringBuilder(items * 2 + 1).append('[');
        for (int index = 0; index < items; index++) {
            if (index > 0) {
                yaml.append(',');
            }
            yaml.append('a');
        }
        return yaml.append(']').toString();
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
