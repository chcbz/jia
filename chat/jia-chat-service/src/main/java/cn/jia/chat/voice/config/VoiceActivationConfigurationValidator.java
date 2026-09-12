package cn.jia.chat.voice.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Fail-fast trust-boundary validation for any attempted voice activation. */
public final class VoiceActivationConfigurationValidator {
    public static final String PROVIDER = "openai-compatible";
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String TRANSCRIPTION_MODEL = "whisper-1";
    public static final String SYNTHESIS_MODEL = "gpt-4o-mini-tts";
    public static final String SYNTHESIS_VOICE = "alloy";
    private static final int MIN_HMAC_BYTES = 32;
    private static final int AES_256_BYTES = 32;

    public VoiceActivationConfigurationValidator(
            VoiceSpeechProperties properties, SpringAiOpenAiVoiceFacade openAi) {
        validate(properties, openAi);
    }

    static void validate(VoiceSpeechProperties properties, SpringAiOpenAiVoiceFacade openAi) {
        if (properties == null || !activationRequested(properties)) {
            return;
        }

        List<String> failures = new ArrayList<>();
        VoiceSpeechProperties.Transcription transcription = properties.getTranscription();
        VoiceSpeechProperties.Synthesis synthesis = properties.getSynthesis();

        requireExact(failures, "transcription.provider", transcription.getProvider(), PROVIDER);
        requireExact(failures, "transcription.model", transcription.getModel(), TRANSCRIPTION_MODEL);
        validateConnection(failures, "transcription", openAi == null ? null : openAi.transcription(),
                properties.getCompatibilityGatewayAllowlist());

        requireExact(failures, "synthesis.provider", synthesis.getProvider(), PROVIDER);
        requireExact(failures, "synthesis.model", synthesis.getModel(), SYNTHESIS_MODEL);
        requireExact(failures, "synthesis.provider-voice", synthesis.getProviderVoice(), SYNTHESIS_VOICE);
        validateConnection(failures, "synthesis", openAi == null ? null : openAi.synthesis(),
                properties.getCompatibilityGatewayAllowlist());

        byte[] hmacSecret = utf8(properties.getIdentityHmacSecret());
        if (hmacSecret == null || hmacSecret.length < MIN_HMAC_BYTES) {
            failures.add("identity-hmac-secret must contain at least 32 UTF-8 bytes");
        }

        byte[] cacheKey = decodeAes256Key(properties.getCacheEncryptionKey());
        if (cacheKey == null) {
            failures.add("cache-encryption-key must be Base64 encoding of exactly 32 bytes");
        }

        if (hmacSecret != null && hmacSecret.length >= MIN_HMAC_BYTES && openAi != null) {
            rejectSameSecret(failures, hmacSecret, utf8(openAi.transcription().apiKey()),
                    "identity-hmac-secret must differ from the resolved transcription API key");
            rejectSameSecret(failures, hmacSecret, utf8(openAi.synthesis().apiKey()),
                    "identity-hmac-secret must differ from the resolved synthesis API key");
            rejectSameSecret(failures, hmacSecret, cacheKey,
                    "identity-hmac-secret must differ from the decoded cache-encryption-key");
            rejectSameSecret(failures, hmacSecret, utf8(properties.getCacheEncryptionKey()),
                    "identity-hmac-secret must differ from cache-encryption-key");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid Juyi Hall voice activation configuration: " + String.join("; ", failures));
        }
    }

    public static boolean isValidApiKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank()
                && apiKey.codePoints().noneMatch(Character::isISOControl);
    }

    public static byte[] decodeAes256Key(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            return decoded.length == AES_256_BYTES ? decoded : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void validateConnection(
            List<String> failures,
            String name,
            SpringAiOpenAiVoiceFacade.Connection connection,
            Set<String> allowlist) {
        if (connection == null || !connection.hasExplicitGateway()) {
            failures.add(name + " gateway must be explicitly configured through spring.ai.openai");
            return;
        }
        if (!isAllowedHttpsGateway(connection.baseUrl(), allowlist)) {
            failures.add(name + " gateway must be an allowed HTTPS compatibility gateway");
        }
        if (!isValidApiKey(connection.apiKey())) {
            failures.add(name + " API key must be non-blank and contain no control characters");
        }
    }

    public static boolean isAllowedHttpsGateway(String value, Set<String> allowlist) {
        if (value == null || allowlist == null || !allowlist.contains(value)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String rawPath = uri.getRawPath();
            return value.equals(uri.toASCIIString())
                    && "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && isUnambiguousBasePath(rawPath);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean isUnambiguousBasePath(String rawPath) {
        if (rawPath == null || rawPath.length() < 2 || !rawPath.startsWith("/")
                || rawPath.endsWith("/") || rawPath.contains("//")
                || rawPath.contains("\\") || rawPath.contains("%")
                || rawPath.contains(";")) {
            return false;
        }
        for (String segment : rawPath.substring(1).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean activationRequested(VoiceSpeechProperties properties) {
        return properties.isEnabled()
                || properties.getTranscription().isEnabled()
                || properties.getSynthesis().isEnabled();
    }

    private static void requireExact(
            List<String> failures, String name, String actual, String expected) {
        if (!expected.equals(actual)) {
            failures.add(name + " must equal " + expected);
        }
    }

    private static void rejectSameSecret(
            List<String> failures, byte[] left, byte[] right, String message) {
        if (right != null && MessageDigest.isEqual(left, right)) {
            failures.add(message);
        }
    }

    private static byte[] utf8(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }
}
