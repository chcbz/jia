package cn.jia.chat.voice.state;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.config.VoiceSpeechProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class VoiceDigests {
    private static final byte[] CONTRACT = "cyf-voice-v1".getBytes(StandardCharsets.UTF_8);
    private final byte[] identityHmacSecret;

    public VoiceDigests(VoiceSpeechProperties properties) {
        String secret = properties.getIdentityHmacSecret();
        this.identityHmacSecret = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean available() {
        return identityHmacSecret != null && identityHmacSecret.length >= 32;
    }

    public String identityScope(VoiceIdentity identity) {
        if (!available()) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, null);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(identityHmacSecret, "HmacSHA256"));
            updateLengthPrefixed(mac, identity.jiacn());
            updateLengthPrefixed(mac, identity.clientId());
            updateLengthPrefixed(mac, identity.subject());
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException exception) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, null);
        }
    }

    public String transcription(String language, String mediaType, byte[] audioDigest) {
        MessageDigest digest = sha256();
        updateLengthPrefixed(digest, CONTRACT);
        updateLengthPrefixed(digest, VoiceOperation.TRANSCRIPTION.namespace().getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, language.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, mediaType.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, audioDigest);
        return HexFormat.of().formatHex(digest.digest());
    }

    public String synthesis(String text, String voice, String format) {
        MessageDigest digest = sha256();
        updateLengthPrefixed(digest, CONTRACT);
        updateLengthPrefixed(digest, VoiceOperation.SYNTHESIS.namespace().getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, voice.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, format.getBytes(StandardCharsets.UTF_8));
        updateLengthPrefixed(digest, text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void updateLengthPrefixed(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        mac.update(bytes);
    }

    private static void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
        digest.update(value);
    }
}
