package cn.jia.chat.voice.state;

import cn.jia.chat.voice.config.VoiceSpeechProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class VoicePayloadCipher {
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public VoicePayloadCipher(VoiceSpeechProperties properties) {
        byte[] decoded = decodeKey(properties.getCacheEncryptionKey());
        this.key = decoded == null ? null : new SecretKeySpec(decoded, "AES");
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(byte[] payload) {
        if (key == null) {
            throw new VoiceStateUnavailableException();
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(payload);
            ByteBuffer output = ByteBuffer.allocate(1 + IV_BYTES + encrypted.length);
            output.put(VERSION).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(output.array());
        } catch (GeneralSecurityException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    public byte[] decrypt(String encoded) {
        if (key == null || encoded == null || encoded.isEmpty()) {
            throw new VoiceStateUnavailableException();
        }
        try {
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length <= 1 + IV_BYTES || value[0] != VERSION) {
                throw new GeneralSecurityException("invalid encrypted payload");
            }
            byte[] iv = Arrays.copyOfRange(value, 1, 1 + IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(value, 1 + IV_BYTES, value.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            return key.length == 32 ? key : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
