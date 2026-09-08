package cn.jia.build.security;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** Value-redacted API-key classifier for bounded YAML and Java properties resources. */
final class ConfigurationSecurityClassifier {
    private static final int MAX_BYTES = 1 << 20;
    private static final int MAX_LINES = 10_000;
    private static final Pattern ENCRYPTED = Pattern.compile("(?s)ENC\\(.+\\)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_.-]*:?\\}");
    private static final Pattern DUMMY = Pattern.compile(
            "(?i)(?:test-only-dummy|dummy|fake|mock|placeholder|example|changeme|not-a-real)(?:[-_.:].*)?");
    private static final Pattern SAFE_DIAGNOSTIC_API_KEY = Pattern.compile(
            "[-A-Za-z0-9_.]*api-key[-A-Za-z0-9_.]*");

    record Match(PublicArtifactVerifier.FailureCode code, String key) {
        Match {
            if (code != PublicArtifactVerifier.FailureCode.API_KEY_VIOLATION || key == null) {
                throw new IllegalArgumentException("invalid-configuration-match");
            }
        }
    }

    private ConfigurationSecurityClassifier() { }

    static boolean isConfigurationResource(String memberName) {
        String normalized = PublicArtifactVerifier.normalize(memberName);
        return normalized.endsWith(".properties")
                || normalized.endsWith(".yml")
                || normalized.endsWith(".yaml");
    }

    static List<Match> classify(String memberName, byte[] content)
            throws PublicArtifactVerifier.VerificationException {
        if (content.length > MAX_BYTES) {
            throw parseFailure();
        }
        String normalized = PublicArtifactVerifier.normalize(memberName);
        if (normalized.endsWith(".properties")) {
            return classifyProperties(content);
        }
        if (normalized.endsWith(".yml") || normalized.endsWith(".yaml")) {
            return classifyYaml(content);
        }
        return List.of();
    }

    private static List<Match> classifyProperties(byte[] content)
            throws PublicArtifactVerifier.VerificationException {
        String latin1 = new String(content, StandardCharsets.ISO_8859_1);
        boolean hasBom = content.length >= 3 && content[0] == (byte) 0xef
                && content[1] == (byte) 0xbb && content[2] == (byte) 0xbf;
        String utf8;
        try {
            utf8 = decodeUtf8(content);
        } catch (CharacterCodingException ignored) {
            // A BOM commits to UTF-8: malformed marked input must not fall back.
            if (hasBom) {
                throw parseFailure();
            }
            return classifyPropertiesText(latin1);
        }
        List<Match> violations = new ArrayList<>(classifyPropertiesText(utf8));
        // Unmarked properties may be read as Latin-1 or UTF-8. Neither interpretation
        // may hide a violation; BOM input keeps the existing stripped UTF-8 semantics.
        if (!hasBom && !utf8.equals(latin1)) {
            for (Match match : classifyPropertiesText(latin1)) {
                if (!violations.contains(match)) {
                    violations.add(match);
                }
            }
        }
        return List.copyOf(violations);
    }

    private static List<Match> classifyPropertiesText(String text)
            throws PublicArtifactVerifier.VerificationException {
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        if (lines.length > MAX_LINES) {
            throw parseFailure();
        }
        List<Match> violations = new ArrayList<>();
        StringBuilder logical = new StringBuilder();
        try {
            for (String line : lines) {
                logical.append(line).append('\n');
                if (hasContinuation(line)) {
                    continue;
                }
                evaluateLogicalProperty(logical.toString(), violations);
                logical.setLength(0);
            }
            if (!logical.isEmpty()) {
                evaluateLogicalProperty(logical.toString(), violations);
            }
        } catch (IllegalArgumentException | IOException ignored) {
            throw parseFailure();
        }
        return List.copyOf(violations);
    }

    private static void evaluateLogicalProperty(String logical, List<Match> violations)
            throws IOException, PublicArtifactVerifier.VerificationException {
        Properties declaration = new Properties();
        declaration.load(new StringReader(logical));
        if (declaration.size() > 1) {
            throw new IllegalArgumentException("multiple-logical-properties");
        }
        for (String key : declaration.stringPropertyNames()) {
            if (isApiKey(key) && !isAllowedValue(declaration.getProperty(key))) {
                addViolation(violations, key);
            }
        }
    }

    private static boolean hasContinuation(String line) {
        int backslashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static List<Match> classifyYaml(byte[] content)
            throws PublicArtifactVerifier.VerificationException {
        List<Match> violations = new ArrayList<>();
        for (BoundedYamlParser.Node document : BoundedYamlParser.parse(content)) {
            traverse(document, violations, 0);
        }
        return List.copyOf(violations);
    }

    private static void traverse(BoundedYamlParser.Node node, List<Match> violations, int depth)
            throws PublicArtifactVerifier.VerificationException {
        if (depth > 64) {
            throw parseFailure();
        }
        if (node.kind == BoundedYamlParser.Kind.MAPPING) {
            for (BoundedYamlParser.Pair pair : node.pairs) {
                if (isApiKey(pair.key()) && !isAllowedNode(pair.value())) {
                    addViolation(violations, pair.key());
                }
                traverse(pair.value(), violations, depth + 1);
            }
        } else if (node.kind == BoundedYamlParser.Kind.SEQUENCE) {
            for (BoundedYamlParser.Node item : node.items) {
                traverse(item, violations, depth + 1);
            }
        }
    }

    private static void addViolation(List<Match> violations, String rawKey)
            throws PublicArtifactVerifier.VerificationException {
        String key = canonicalKey(rawKey);
        if (!SAFE_DIAGNOSTIC_API_KEY.matcher(key).matches()) {
            throw parseFailure();
        }
        violations.add(new Match(PublicArtifactVerifier.FailureCode.API_KEY_VIOLATION, key));
    }

    private static boolean isAllowedNode(BoundedYamlParser.Node node) {
        return node.kind == BoundedYamlParser.Kind.NULL
                || node.kind == BoundedYamlParser.Kind.SCALAR && isAllowedValue(node.scalar);
    }

    static boolean isApiKey(String key) {
        return canonicalKey(key).contains("api-key");
    }

    static String canonicalKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static boolean isAllowedValue(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        return value.isEmpty()
                || ENCRYPTED.matcher(value).matches()
                || PLACEHOLDER.matcher(value).matches()
                || DUMMY.matcher(value).matches();
    }

    private static String decodeUtf8(byte[] content) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        return !text.isEmpty() && text.charAt(0) == '\ufeff' ? text.substring(1) : text;
    }

    private static PublicArtifactVerifier.VerificationException parseFailure() {
        return new PublicArtifactVerifier.VerificationException(
                PublicArtifactVerifier.FailureCode.CONFIGURATION_PARSE_ERROR);
    }
}
