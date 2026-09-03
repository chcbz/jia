package cn.jia.chat.voice.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Fail-fast trust-boundary validation for any attempted voice activation. */
public final class VoiceActivationConfigurationValidator {
    public static final String PROVIDER = "openai-compatible";
    public static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String TRANSCRIPTION_MODEL = "whisper-1";
    public static final String SYNTHESIS_MODEL = "gpt-4o-mini-tts";
    public static final String SYNTHESIS_VOICE = "alloy";
    private static final int MIN_HMAC_BYTES = 32;
    private static final int AES_256_BYTES = 32;

    public VoiceActivationConfigurationValidator(VoiceSpeechProperties properties) {
        validate(properties);
    }

    static void validate(VoiceSpeechProperties properties) {
        if (properties == null || !activationRequested(properties)) {
            return;
        }

        List<String> failures = new ArrayList<>();
        VoiceSpeechProperties.Transcription transcription = properties.getTranscription();
        VoiceSpeechProperties.Synthesis synthesis = properties.getSynthesis();

        requireExact(failures, "transcription.provider", transcription.getProvider(), PROVIDER);
        requireExact(failures, "transcription.base-url", transcription.getBaseUrl(), OPENAI_BASE_URL);
        requireExact(failures, "transcription.model", transcription.getModel(), TRANSCRIPTION_MODEL);
        requireApiKey(failures, "transcription.api-key", transcription.getApiKey());

        requireExact(failures, "synthesis.provider", synthesis.getProvider(), PROVIDER);
        requireExact(failures, "synthesis.base-url", synthesis.getBaseUrl(), OPENAI_BASE_URL);
        requireExact(failures, "synthesis.model", synthesis.getModel(), SYNTHESIS_MODEL);
        requireExact(failures, "synthesis.provider-voice", synthesis.getProviderVoice(), SYNTHESIS_VOICE);
        requireApiKey(failures, "synthesis.api-key", synthesis.getApiKey());

        byte[] hmacSecret = utf8(properties.getIdentityHmacSecret());
        if (hmacSecret == null || hmacSecret.length < MIN_HMAC_BYTES) {
            failures.add("identity-hmac-secret must contain at least 32 UTF-8 bytes");
        }

        byte[] cacheKey = decodeAes256Key(properties.getCacheEncryptionKey());
        if (cacheKey == null) {
            failures.add("cache-encryption-key must be Base64 encoding of exactly 32 bytes");
        }

        if (hmacSecret != null && hmacSecret.length >= MIN_HMAC_BYTES) {
            rejectSameSecret(failures, hmacSecret, utf8(transcription.getApiKey()),
                    "identity-hmac-secret must differ from transcription.api-key");
            rejectSameSecret(failures, hmacSecret, utf8(synthesis.getApiKey()),
                    "identity-hmac-secret must differ from synthesis.api-key");
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

    private static void requireApiKey(List<String> failures, String name, String apiKey) {
        if (!isValidApiKey(apiKey)) {
            failures.add(name + " must be non-blank and contain no control characters");
        }
    }

    private static byte[] utf8(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    private static void rejectSameSecret(
            List<String> failures, byte[] left, byte[] right, String message) {
        if (right != null && MessageDigest.isEqual(left, right)) {
            failures.add(message);
        }
    }
}
