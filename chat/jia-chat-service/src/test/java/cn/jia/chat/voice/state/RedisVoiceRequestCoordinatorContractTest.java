package cn.jia.chat.voice.state;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisVoiceRequestCoordinatorContractTest {
    @Test
    void beginLuaChecksExactExistingStateBeforeAtomicQuotaAndLeaseReservation() throws Exception {
        String script = script("BEGIN_SCRIPT");
        assertTrue(script.indexOf("HGET', KEYS[1], 'digest'") < script.indexOf("INCR', KEYS[2]"));
        assertTrue(script.contains("digest ~= ARGV[1]"));
        assertTrue(script.contains("state == 'SUCCEEDED'"));
        assertTrue(script.contains("state == 'FAILED_UNKNOWN'"));
        assertTrue(script.contains("ZREMRANGEBYSCORE"));
        assertTrue(script.contains("ZCARD', KEYS[4]) >= 1"));
        assertTrue(script.contains("ZCARD', KEYS[5]) >= tonumber(ARGV[5])"));
        assertTrue(script.contains("HSET', KEYS[1], 'digest', ARGV[1], 'state', 'IN_PROGRESS'"));
    }

    @Test
    void terminalAndReleaseLuaAreCompareAndSetByDigestAndMatchingLeaseToken() throws Exception {
        String terminal = script("TERMINAL_SCRIPT");
        String release = script("RELEASE_SCRIPT");
        assertTrue(terminal.contains("state') ~= 'IN_PROGRESS'"));
        assertTrue(terminal.contains("digest') ~= ARGV[1]"));
        assertTrue(terminal.contains("lease') ~= ARGV[2]"));
        assertTrue(release.contains("lease ~= ARGV[1]"));
        assertTrue(release.contains("ZREM', KEYS[2], ARGV[1]"));
        assertEquals(Duration.ofMinutes(10).toMillis(), RedisVoiceRequestCoordinator.SUCCEEDED_TTL_MS);
        assertEquals(Duration.ofMinutes(2).toMillis(), RedisVoiceRequestCoordinator.FAILED_KNOWN_TTL_MS);
        assertEquals(Duration.ofMinutes(10).toMillis(), RedisVoiceRequestCoordinator.FAILED_UNKNOWN_TTL_MS);
    }

    @Test
    void encryptedPayloadRoundTripsAndTamperingFailsClosed() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setCacheEncryptionKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        VoicePayloadCipher cipher = new VoicePayloadCipher(properties);
        String encrypted = cipher.encrypt(new byte[]{1, 2, 3, 4});
        assertTrue(!encrypted.contains("AQIDBA=="));
        assertEquals(4, cipher.decrypt(encrypted).length);
        char replacement = encrypted.charAt(encrypted.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;
        org.junit.jupiter.api.Assertions.assertThrows(
                VoiceStateUnavailableException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void operationNamespacesAndRequestIdsAreDistinctInputsToStateScope() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setIdentityHmacSecret("01234567890123456789012345678901");
        VoiceDigests digests = new VoiceDigests(properties);
        String scope = digests.identityScope(new VoiceIdentity("tenant", "client", "subject"));
        assertEquals(64, scope.length());
        assertTrue(!VoiceOperation.TRANSCRIPTION.namespace().equals(VoiceOperation.SYNTHESIS.namespace()));
    }


    @Test
    void operationsSeparateRequestStateButShareVoiceQuotaAndConcurrencyKeys() throws Exception {
        Method keysMethod = RedisVoiceRequestCoordinator.class.getDeclaredMethod(
                "keys", VoiceOperation.class, String.class, String.class);
        keysMethod.setAccessible(true);
        Object stt = keysMethod.invoke(null, VoiceOperation.TRANSCRIPTION, "scope", "request");
        Object tts = keysMethod.invoke(null, VoiceOperation.SYNTHESIS, "scope", "request");

        assertTrue(!key(stt, "stateKey").equals(key(tts, "stateKey")));
        assertEquals(key(stt, "minuteRate"), key(tts, "minuteRate"));
        assertEquals(key(stt, "hourRate"), key(tts, "hourRate"));
        assertEquals(key(stt, "identityLeases"), key(tts, "identityLeases"));
        assertEquals(key(stt, "globalLeases"), key(tts, "globalLeases"));
    }


    private static String key(Object keys, String accessor) throws Exception {
        Method method = keys.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return String.valueOf(method.invoke(keys));
    }

    @SuppressWarnings("unchecked")
    private static String script(String fieldName) throws Exception {
        Field field = RedisVoiceRequestCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((DefaultRedisScript<Object>) field.get(null)).getScriptAsString();
    }
}
