package cn.jia.chat.voice.state;

import cn.jia.chat.voice.config.VoiceActivationConfigurationValidator;
import cn.jia.chat.voice.config.VoiceSpeechProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class VoicePayloadCipher {
    private static final byte CIPHERTEXT_VERSION = 2;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public VoicePayloadCipher(VoiceSpeechProperties properties) {
        byte[] decoded = VoiceActivationConfigurationValidator.decodeAes256Key(
                properties.getCacheEncryptionKey());
        this.key = decoded == null ? null : new SecretKeySpec(decoded, "AES");
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(
            byte[] payload,
            VoiceOperation operation,
            String identityScope,
            String requestId,
            String digest,
            String contentType) {
        if (key == null || payload == null) {
            throw new VoiceStateUnavailableException();
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(operation, identityScope, requestId, digest, contentType));
            byte[] encrypted = cipher.doFinal(payload);
            ByteBuffer output = ByteBuffer.allocate(1 + IV_BYTES + encrypted.length);
            output.put(CIPHERTEXT_VERSION).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(output.array());
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    public byte[] decrypt(
            String encoded,
            VoiceOperation operation,
            String identityScope,
            String requestId,
            String digest,
            String contentType) {
        if (key == null || encoded == null || encoded.isEmpty()) {
            throw new VoiceStateUnavailableException();
        }
        try {
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length <= 1 + IV_BYTES || value[0] != CIPHERTEXT_VERSION) {
                throw new GeneralSecurityException("invalid encrypted payload");
            }
            byte[] iv = Arrays.copyOfRange(value, 1, 1 + IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(value, 1 + IV_BYTES, value.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(operation, identityScope, requestId, digest, contentType));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    private static byte[] aad(
            VoiceOperation operation,
            String identityScope,
            String requestId,
            String digest,
            String contentType) {
        if (operation == null || identityScope == null || requestId == null
                || digest == null || contentType == null) {
            throw new VoiceStateUnavailableException();
        }
        byte[][] fields = {
                VoiceContract.versionBytes(),
                operation.namespace().getBytes(StandardCharsets.UTF_8),
                identityScope.getBytes(StandardCharsets.UTF_8),
                requestId.getBytes(StandardCharsets.UTF_8),
                digest.getBytes(StandardCharsets.UTF_8),
                contentType.getBytes(StandardCharsets.UTF_8)
        };
        int size = 0;
        for (byte[] field : fields) {
            size = Math.addExact(size, Math.addExact(Integer.BYTES, field.length));
        }
        ByteBuffer aad = ByteBuffer.allocate(size);
        for (byte[] field : fields) {
            aad.putInt(field.length).put(field);
        }
        return aad.array();
    }
}
