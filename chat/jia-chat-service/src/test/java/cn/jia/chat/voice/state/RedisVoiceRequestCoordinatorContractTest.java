package cn.jia.chat.voice.state;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisVoiceRequestCoordinatorContractTest {
    @Test
    void preAdmissionLuaOwnsRateAndConcurrencyBeforeAnyDigestOrRequestState() throws Exception {
        String admit = script("ADMIT_SCRIPT");
        assertTrue(!admit.contains("digest"));
        assertTrue(!admit.contains("HGET"));
        assertTrue(admit.indexOf("GET', KEYS[1]") < admit.indexOf("INCR', KEYS[1]"));
        assertTrue(admit.indexOf("ZCARD', KEYS[3]") < admit.indexOf("INCR', KEYS[1]"));
        assertTrue(admit.contains("ZADD', KEYS[3]"));
        assertTrue(admit.contains("ZADD', KEYS[4]"));

        String beginAdmitted = script("BEGIN_ADMITTED_SCRIPT");
        assertTrue(beginAdmitted.indexOf("ZSCORE', KEYS[2], ARGV[2]")
                < beginAdmitted.indexOf("HGET', KEYS[1], 'digest'"));
        assertTrue(beginAdmitted.contains("ADMISSION_INVALID"));
        assertTrue(beginAdmitted.contains("digest ~= ARGV[1]"));
        assertTrue(beginAdmitted.contains("state', 'IN_PROGRESS'"));
        assertTrue(!beginAdmitted.contains("INCR"));
    }

    @Test
    void beginLuaChecksExactExistingStateBeforeAtomicQuotaAndLeaseReservation() throws Exception {
        String script = script("BEGIN_SCRIPT");
        assertTrue(script.indexOf("HGET', KEYS[1], 'digest'") < script.indexOf("INCR', KEYS[2]"));
        assertTrue(script.contains("digest ~= ARGV[1]"));
        assertTrue(script.contains("state == 'SUCCEEDED'"));
        assertTrue(script.contains("state == 'FAILED_UNKNOWN'"));
        assertTrue(script.contains("state', 'FAILED_UNKNOWN'"));
        assertTrue(script.contains("ZSCORE', KEYS[4], lease"));
        assertTrue(script.contains("PEXPIRE', KEYS[1], ARGV[8]"));
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
        assertTrue(release.contains("state') == 'IN_PROGRESS'"));
        assertTrue(release.contains("state', 'FAILED_UNKNOWN'"));
        assertTrue(release.indexOf("state', 'FAILED_UNKNOWN'")
                < release.indexOf("ZREM', KEYS[2], ARGV[1]"));
        assertTrue(release.contains("PEXPIRE', KEYS[1], ARGV[2]"));
        assertTrue(release.contains("ZREM', KEYS[2], ARGV[1]"));
        assertTrue(!release.contains("lease ~= ARGV[1] then return"));
        assertEquals(Duration.ofMinutes(10).toMillis(), RedisVoiceRequestCoordinator.SUCCEEDED_TTL_MS);
        assertEquals(Duration.ofMinutes(2).toMillis(), RedisVoiceRequestCoordinator.FAILED_KNOWN_TTL_MS);
        assertEquals(Duration.ofMinutes(10).toMillis(), RedisVoiceRequestCoordinator.FAILED_UNKNOWN_TTL_MS);
    }

    @Test
    void realRedisExecutesAtomicTerminalReplayAndTokenMismatchContracts() throws Exception {
        try (OwnedRedisServer server = OwnedRedisServer.start()) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(server.host(), server.port());
            factory.afterPropertiesSet();
            factory.start();
            try {
                assertRedisRuntimeCapabilities(factory);
                VoiceSpeechProperties properties = new VoiceSpeechProperties();
                properties.setCacheEncryptionKey(
                        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
                RedisVoiceRequestCoordinator coordinator = new RedisVoiceRequestCoordinator(
                        factory, properties, new VoicePayloadCipher(properties));

                assertSucceededReplayAndConflicts(coordinator);
                assertFailedKnownReplay(coordinator);
                assertFailedUnknownReplay(coordinator);
                assertTokenMismatchCannotTerminateOrRelease(coordinator);
                assertConcurrentBeginIsAtomic(coordinator);
                assertRedisReplayRejectsCiphertextTupleTransplants(factory, coordinator);
                assertPreAdmissionIsBilledAndReleased(factory);
                assertReplayAndConflictAdmissionsReleaseOwnLease(factory);
                assertConcurrentPreAdmissionReleaseRestoresEverySlot(factory);

                assertScriptLoaded(factory, "ADMIT_SCRIPT");
                assertScriptLoaded(factory, "BEGIN_ADMITTED_SCRIPT");
                assertScriptLoaded(factory, "BEGIN_SCRIPT");
                assertScriptLoaded(factory, "TERMINAL_SCRIPT");
                assertScriptLoaded(factory, "RELEASE_SCRIPT");
            } finally {
                factory.destroy();
            }
        }
    }

    private static void assertPreAdmissionIsBilledAndReleased(
            LettuceConnectionFactory factory) {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setPerMinute(2);
        properties.setPerHour(2);
        properties.setGlobalConcurrency(1);
        properties.setCacheEncryptionKey(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        RedisVoiceRequestCoordinator coordinator = new RedisVoiceRequestCoordinator(
                factory, properties, new VoicePayloadCipher(properties));

        VoiceAdmission first = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "billed-scope", "billed-request-1"));
        assertEquals(VoiceAdmissionResult.Outcome.CONCURRENCY_LIMITED,
                coordinator.admit(VoiceOperation.TRANSCRIPTION,
                        "other-scope", "global-blocked").outcome());
        coordinator.release(first);

        VoiceAdmission second = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "billed-scope", "billed-request-2"));
        VoiceBeginResult begun = coordinator.begin(second, "billed-digest-2");
        assertEquals(VoiceBeginResult.Outcome.RESERVED, begun.outcome());
        coordinator.failKnown(begun.reservation());
        coordinator.release(second);

        assertEquals(VoiceAdmissionResult.Outcome.RATE_LIMITED,
                coordinator.admit(VoiceOperation.TRANSCRIPTION,
                        "billed-scope", "billed-request-3").outcome());
        assertThrows(VoiceStateUnavailableException.class,
                () -> coordinator.begin(new VoiceAdmission(
                        VoiceOperation.TRANSCRIPTION, "billed-scope",
                        "forged-request", "forged-lease"), "forged-digest"));
    }

    private static void assertReplayAndConflictAdmissionsReleaseOwnLease(
            LettuceConnectionFactory factory) {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setPerMinute(10);
        properties.setPerHour(10);
        properties.setGlobalConcurrency(1);
        properties.setCacheEncryptionKey(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        RedisVoiceRequestCoordinator coordinator = new RedisVoiceRequestCoordinator(
                factory, properties, new VoicePayloadCipher(properties));

        String scope = "admitted-replay-scope";
        String request = "admitted-replay-request";
        String digest = "admitted-replay-digest";
        VoiceAdmission original = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, scope, request));
        VoiceBeginResult first = coordinator.begin(original, digest);
        coordinator.succeed(first.reservation(),
                new VoiceCachedResult(new byte[]{1}, "application/json"));
        coordinator.release(original);

        VoiceAdmission replayAdmission = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, scope, request));
        assertEquals(VoiceBeginResult.Outcome.REPLAY,
                coordinator.begin(replayAdmission, digest).outcome());
        coordinator.release(replayAdmission);
        VoiceAdmission afterReplay = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "after-replay-scope", "after-replay-request"));
        coordinator.release(afterReplay);

        VoiceAdmission conflictAdmission = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, scope, request));
        assertEquals(VoiceBeginResult.Outcome.IDEMPOTENCY_CONFLICT,
                coordinator.begin(conflictAdmission, "different-digest").outcome());
        coordinator.release(conflictAdmission);
        VoiceAdmission afterConflict = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "after-conflict-scope", "after-conflict-request"));
        coordinator.release(afterConflict);
    }

    private static void assertConcurrentPreAdmissionReleaseRestoresEverySlot(
            LettuceConnectionFactory factory) throws Exception {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setPerMinute(10);
        properties.setPerHour(10);
        properties.setGlobalConcurrency(2);
        properties.setCacheEncryptionKey(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        RedisVoiceRequestCoordinator coordinator = new RedisVoiceRequestCoordinator(
                factory, properties, new VoicePayloadCipher(properties));
        VoiceAdmission left = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "release-left", "release-request-left"));
        VoiceAdmission right = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "release-right", "release-request-right"));
        assertEquals(VoiceAdmissionResult.Outcome.CONCURRENCY_LIMITED,
                coordinator.admit(VoiceOperation.TRANSCRIPTION,
                        "release-blocked", "release-request-blocked").outcome());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> leftRelease = executor.submit(() -> coordinator.release(left));
            Future<?> rightRelease = executor.submit(() -> coordinator.release(right));
            leftRelease.get(5, TimeUnit.SECONDS);
            rightRelease.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        VoiceAdmission nextLeft = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "release-next-left", "release-next-request-left"));
        VoiceAdmission nextRight = admitted(coordinator.admit(
                VoiceOperation.TRANSCRIPTION, "release-next-right", "release-next-request-right"));
        coordinator.release(nextLeft);
        coordinator.release(nextRight);
    }

    private static VoiceAdmission admitted(VoiceAdmissionResult result) {
        assertEquals(VoiceAdmissionResult.Outcome.ADMITTED, result.outcome());
        return result.admission();
    }

    private static void assertSucceededReplayAndConflicts(
            RedisVoiceRequestCoordinator coordinator) {
        VoiceBeginResult first = coordinator.begin(VoiceOperation.TRANSCRIPTION,
                "success-scope", "success-request", "success-digest");
        assertEquals(VoiceBeginResult.Outcome.RESERVED, first.outcome());
        VoiceReservation reservation = first.reservation();
        VoiceCachedResult expected = new VoiceCachedResult(
                new byte[]{1, 2, 3, 4}, "application/json");

        coordinator.succeed(reservation, expected);
        assertThrows(VoiceStateUnavailableException.class,
                () -> coordinator.failKnown(reservation));
        coordinator.release(reservation);

        VoiceBeginResult replay = coordinator.begin(VoiceOperation.TRANSCRIPTION,
                "success-scope", "success-request", "success-digest");
        assertEquals(VoiceBeginResult.Outcome.REPLAY, replay.outcome());
        assertArrayEquals(expected.payload(), replay.replay().payload());
        assertEquals(expected.contentType(), replay.replay().contentType());
        assertEquals(VoiceBeginResult.Outcome.IDEMPOTENCY_CONFLICT,
                coordinator.begin(VoiceOperation.TRANSCRIPTION,
                        "success-scope", "success-request", "different-digest").outcome());
    }

    private static void assertFailedKnownReplay(RedisVoiceRequestCoordinator coordinator) {
        VoiceBeginResult first = coordinator.begin(VoiceOperation.TRANSCRIPTION,
                "known-scope", "known-request", "known-digest");
        assertEquals(VoiceBeginResult.Outcome.RESERVED, first.outcome());
        coordinator.failKnown(first.reservation());
        assertThrows(VoiceStateUnavailableException.class,
                () -> coordinator.failUnknown(first.reservation()));
        coordinator.release(first.reservation());

        assertEquals(VoiceBeginResult.Outcome.FAILED_KNOWN,
                coordinator.begin(VoiceOperation.TRANSCRIPTION,
                        "known-scope", "known-request", "known-digest").outcome());
        assertEquals(VoiceBeginResult.Outcome.IDEMPOTENCY_CONFLICT,
                coordinator.begin(VoiceOperation.TRANSCRIPTION,
                        "known-scope", "known-request", "different-digest").outcome());
    }

    private static void assertFailedUnknownReplay(RedisVoiceRequestCoordinator coordinator) {
        VoiceBeginResult first = coordinator.begin(VoiceOperation.SYNTHESIS,
                "unknown-scope", "unknown-request", "unknown-digest");
        assertEquals(VoiceBeginResult.Outcome.RESERVED, first.outcome());
        coordinator.failUnknown(first.reservation());
        assertThrows(VoiceStateUnavailableException.class,
                () -> coordinator.succeed(first.reservation(),
                        new VoiceCachedResult(new byte[]{9}, "audio/mpeg")));
        coordinator.release(first.reservation());

        assertEquals(VoiceBeginResult.Outcome.RESULT_UNKNOWN,
                coordinator.begin(VoiceOperation.SYNTHESIS,
                        "unknown-scope", "unknown-request", "unknown-digest").outcome());
        assertEquals(VoiceBeginResult.Outcome.IDEMPOTENCY_CONFLICT,
                coordinator.begin(VoiceOperation.SYNTHESIS,
                        "unknown-scope", "unknown-request", "different-digest").outcome());
    }

    private static void assertTokenMismatchCannotTerminateOrRelease(
            RedisVoiceRequestCoordinator coordinator) {
        VoiceBeginResult first = coordinator.begin(VoiceOperation.TRANSCRIPTION,
                "token-scope", "token-request", "token-digest");
        assertEquals(VoiceBeginResult.Outcome.RESERVED, first.outcome());
        VoiceReservation original = first.reservation();
        VoiceReservation forgedLease = new VoiceReservation(original.operation(),
                original.identityScope(), original.requestId(), original.digest(), "forged-lease");
        VoiceReservation forgedDigest = new VoiceReservation(original.operation(),
                original.identityScope(), original.requestId(), "forged-digest",
                original.leaseToken());

        assertThrows(VoiceStateUnavailableException.class,
                () -> coordinator.succeed(forgedLease,
                        new VoiceCachedResult(new byte[]{7}, "application/json")));
        assertThrows(VoiceStateUnavailableException.class,
                () -> coordinator.failKnown(forgedDigest));
        coordinator.release(forgedLease);
        assertEquals(VoiceBeginResult.Outcome.IN_PROGRESS,
                coordinator.begin(VoiceOperation.TRANSCRIPTION,
                        "token-scope", "token-request", "token-digest").outcome());

        coordinator.failUnknown(original);
        coordinator.release(original);
        assertEquals(VoiceBeginResult.Outcome.RESULT_UNKNOWN,
                coordinator.begin(VoiceOperation.TRANSCRIPTION,
                        "token-scope", "token-request", "token-digest").outcome());
    }

    @Test
    void encryptedPayloadBindsEveryReplayTupleFieldAndRejectsLegacyV1() throws Exception {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setCacheEncryptionKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        VoicePayloadCipher cipher = new VoicePayloadCipher(properties);
        byte[] payload = {1, 2, 3, 4};
        String encrypted = cipher.encrypt(payload, VoiceOperation.TRANSCRIPTION,
                "tenant-a", "request-a", "digest-a", "application/json");
        assertTrue(!encrypted.contains("AQIDBA=="));
        assertArrayEquals(payload, cipher.decrypt(encrypted, VoiceOperation.TRANSCRIPTION,
                "tenant-a", "request-a", "digest-a", "application/json"));

        assertThrows(VoiceStateUnavailableException.class,
                () -> cipher.decrypt(encrypted, VoiceOperation.TRANSCRIPTION,
                        "tenant-b", "request-a", "digest-a", "application/json"));
        assertThrows(VoiceStateUnavailableException.class,
                () -> cipher.decrypt(encrypted, VoiceOperation.TRANSCRIPTION,
                        "tenant-a", "request-b", "digest-a", "application/json"));
        assertThrows(VoiceStateUnavailableException.class,
                () -> cipher.decrypt(encrypted, VoiceOperation.SYNTHESIS,
                        "tenant-a", "request-a", "digest-a", "application/json"));
        assertThrows(VoiceStateUnavailableException.class,
                () -> cipher.decrypt(encrypted, VoiceOperation.TRANSCRIPTION,
                        "tenant-a", "request-a", "digest-b", "application/json"));
        assertThrows(VoiceStateUnavailableException.class,
                () -> cipher.decrypt(encrypted, VoiceOperation.TRANSCRIPTION,
                        "tenant-a", "request-a", "digest-a", "audio/mpeg"));

        char replacement = encrypted.charAt(encrypted.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;
        assertThrows(VoiceStateUnavailableException.class,
                () -> cipher.decrypt(tampered, VoiceOperation.TRANSCRIPTION,
                        "tenant-a", "request-a", "digest-a", "application/json"));

        String legacyV1 = encryptLegacyV1(properties.getCacheEncryptionKey(), payload);
        assertThrows(VoiceStateUnavailableException.class,
                () -> cipher.decrypt(legacyV1, VoiceOperation.TRANSCRIPTION,
                        "tenant-a", "request-a", "digest-a", "application/json"));
    }

    private static void assertRedisReplayRejectsCiphertextTupleTransplants(
            LettuceConnectionFactory factory,
            RedisVoiceRequestCoordinator coordinator) throws Exception {
        VoiceOperation sourceOperation = VoiceOperation.TRANSCRIPTION;
        String sourceScope = "cipher-source-scope";
        String sourceRequest = "cipher-source-request";
        String sourceDigest = "cipher-source-digest";
        String sourceContentType = "application/json";
        VoiceBeginResult source = coordinator.begin(
                sourceOperation, sourceScope, sourceRequest, sourceDigest);
        coordinator.succeed(source.reservation(),
                new VoiceCachedResult(new byte[]{4, 3, 2, 1}, sourceContentType));
        coordinator.release(source.reservation());

        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        String sourceKey = stateKey(sourceOperation, sourceScope, sourceRequest);
        String encrypted = String.valueOf(redis.opsForHash().get(sourceKey, "payload"));

        List<ReplayTuple> transplants = List.of(
                new ReplayTuple(VoiceOperation.TRANSCRIPTION, "cipher-target-scope",
                        sourceRequest, sourceDigest, sourceContentType),
                new ReplayTuple(VoiceOperation.TRANSCRIPTION, sourceScope,
                        "cipher-target-request", sourceDigest, sourceContentType),
                new ReplayTuple(VoiceOperation.SYNTHESIS, sourceScope,
                        sourceRequest, sourceDigest, sourceContentType),
                new ReplayTuple(VoiceOperation.TRANSCRIPTION, sourceScope,
                        "cipher-digest-request", "cipher-target-digest", sourceContentType));
        for (ReplayTuple transplant : transplants) {
            String targetKey = stateKey(
                    transplant.operation(), transplant.identityScope(), transplant.requestId());
            redis.opsForHash().putAll(targetKey, Map.of(
                    "digest", transplant.digest(),
                    "state", "SUCCEEDED",
                    "payload", encrypted,
                    "contentType", transplant.contentType()));
            assertThrows(VoiceStateUnavailableException.class,
                    () -> coordinator.begin(transplant.operation(), transplant.identityScope(),
                            transplant.requestId(), transplant.digest()));
            redis.delete(targetKey);
        }

        redis.opsForHash().put(sourceKey, "contentType", "audio/mpeg");
        assertThrows(VoiceStateUnavailableException.class,
                () -> coordinator.begin(
                        sourceOperation, sourceScope, sourceRequest, sourceDigest));
        redis.delete(sourceKey);
    }

    private static String encryptLegacyV1(String encodedKey, byte[] payload) throws Exception {
        byte[] iv = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(encodedKey), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(payload);
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(1 + iv.length + encrypted.length)
                .put((byte) 1).put(iv).put(encrypted).array());
    }

    private static String stateKey(
            VoiceOperation operation, String identityScope, String requestId) throws Exception {
        Method keysMethod = RedisVoiceRequestCoordinator.class.getDeclaredMethod(
                "keys", VoiceOperation.class, String.class, String.class);
        keysMethod.setAccessible(true);
        return key(keysMethod.invoke(null, operation, identityScope, requestId), "stateKey");
    }

    private record ReplayTuple(
            VoiceOperation operation,
            String identityScope,
            String requestId,
            String digest,
            String contentType) {
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

    private static void assertRedisRuntimeCapabilities(
            LettuceConnectionFactory factory) throws Exception {
        try (RedisConnection connection = factory.getConnection()) {
            Properties info = connection.serverCommands().info("server");
            requireRedis72(info == null ? null : info.getProperty("redis_version"));
            assertEquals("OK", utf8(connection.execute("CLIENT",
                    bytes("SETINFO"), bytes("LIB-NAME"), bytes("jvc-contract"))));
            assertEquals("OK", utf8(connection.execute("CLIENT",
                    bytes("SETINFO"), bytes("LIB-VER"), bytes("1.0"))));
            String sha = connection.scriptingCommands().scriptLoad(
                    bytes("return {KEYS[1],ARGV[1]}"));
            Object response = connection.scriptingCommands().evalSha(sha, ReturnType.MULTI, 1,
                    bytes("capability-key"), bytes("capability-value"));
            assertTrue(response instanceof List<?>);
            List<?> values = (List<?>) response;
            assertEquals(2, values.size());
            assertEquals("capability-key", utf8(values.get(0)));
            assertEquals("capability-value", utf8(values.get(1)));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String utf8(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    private static void requireRedis72(String version) throws IOException {
        Matcher matcher = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(
                version == null ? "" : version);
        if (!matcher.find()) {
            throw new IOException("Redis runtime version is unavailable");
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        if (major < 7 || major == 7 && minor < 2) {
            throw new IOException("Redis 4.x/6.x are incompatible; Redis 7.2+ is required");
        }
    }

    private static void assertScriptLoaded(
            LettuceConnectionFactory factory, String fieldName) throws Exception {
        try (RedisConnection connection = factory.getConnection()) {
            assertEquals(List.of(true),
                    connection.scriptingCommands().scriptExists(scriptObject(fieldName).getSha1()));
        }
    }

    private static void assertConcurrentBeginIsAtomic(
            RedisVoiceRequestCoordinator coordinator) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<VoiceBeginResult> left = executor.submit(() -> concurrentBegin(
                    coordinator, ready, start));
            Future<VoiceBeginResult> right = executor.submit(() -> concurrentBegin(
                    coordinator, ready, start));
            assertTrue(ready.await(2, TimeUnit.SECONDS), "Redis race workers did not become ready");
            start.countDown();
            List<VoiceBeginResult> results = List.of(
                    left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream()
                    .filter(result -> result.outcome() == VoiceBeginResult.Outcome.RESERVED).count());
            assertEquals(1, results.stream()
                    .filter(result -> result.outcome() == VoiceBeginResult.Outcome.IN_PROGRESS).count());
            VoiceReservation reservation = results.stream()
                    .filter(result -> result.reservation() != null)
                    .findFirst().orElseThrow().reservation();
            coordinator.release(reservation);
            assertEquals(VoiceBeginResult.Outcome.RESULT_UNKNOWN, coordinator.begin(
                    VoiceOperation.TRANSCRIPTION, "race-scope", "race-request",
                    "race-digest").outcome());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "Redis race workers did not stop");
        }
    }

    private static VoiceBeginResult concurrentBegin(
            RedisVoiceRequestCoordinator coordinator, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Redis race start was not released");
        }
        return coordinator.begin(VoiceOperation.TRANSCRIPTION, "race-scope", "race-request",
                "race-digest");
    }

    private static String key(Object keys, String accessor) throws Exception {
        Method method = keys.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return String.valueOf(method.invoke(keys));
    }

    private static String script(String fieldName) throws Exception {
        return scriptObject(fieldName).getScriptAsString();
    }

    @SuppressWarnings("unchecked")
    private static DefaultRedisScript<Object> scriptObject(String fieldName) throws Exception {
        Field field = RedisVoiceRequestCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (DefaultRedisScript<Object>) field.get(null);
    }

    private static final class OwnedRedisServer implements AutoCloseable {
        private static final String OWNER_MARKER = "JVC-API-OWNED";
        private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
        private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
        private static final Map<String, String> BINARY_SHA256 = Map.of(
                "redis-server-7.4.1-linux-amd64",
                "ff1628a3c48e4e4e409fac6a72395d1cf181d9c07566f15daf807d7e270259a4",
                "redis-server-7.4.1-linux-arm64",
                "86d14ebaf58d55c2c4513de2617f90b844ddf40c561fd7f350b98648dfa6ebef");

        private final Path root;
        private final Process process;
        private final long pid;
        private final Instant startInstant;
        private final int port;

        private OwnedRedisServer(
                Path root, Process process, Instant startInstant, int port) {
            this.root = root;
            this.process = process;
            this.pid = process.pid();
            this.startInstant = startInstant;
            this.port = port;
        }

        static OwnedRedisServer start() throws Exception {
            Path root = Files.createTempDirectory("jvc-api-redis-");
            Files.writeString(root.resolve(OWNER_MARKER), "owned\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            Files.createDirectory(root.resolve("tmp"));
            Process process = null;
            OwnedRedisServer server = null;
            try {
                Path binary = extractVerifiedBinary(root);
                requireCompatibleVersion(binary);
                int port = freePort();
                Path config = root.resolve("redis.conf");
                Files.writeString(config, String.format(Locale.ROOT, """
                        bind 127.0.0.1
                        port %d
                        protected-mode no
                        save ""
                        appendonly no
                        daemonize no
                        databases 1
                        dir %s
                        dbfilename dump.rdb
                        pidfile %s
                        logfile ""
                        """, port, root, root.resolve("redis.pid")),
                        StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);

                ProcessBuilder builder = new ProcessBuilder(binary.toString(), config.toString());
                builder.directory(root.toFile());
                builder.redirectErrorStream(true);
                builder.redirectOutput(root.resolve("redis.log").toFile());
                builder.environment().clear();
                builder.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
                builder.environment().put("LANG", "C");
                builder.environment().put("LC_ALL", "C");
                builder.environment().put("HOME", root.toString());
                builder.environment().put("TMPDIR", root.resolve("tmp").toString());
                process = builder.start();
                Instant startInstant = process.toHandle().info().startInstant()
                        .orElseThrow(() -> new IOException(
                                "Embedded Redis child start identity unavailable"));
                server = new OwnedRedisServer(root, process, startInstant, port);
                server.awaitReady();
                return server;
            } catch (Throwable failure) {
                try {
                    if (server != null) {
                        server.close();
                    } else {
                        stopNewChild(process);
                        deleteOwnedRoot(root);
                    }
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        String host() {
            return "127.0.0.1";
        }

        int port() {
            return port;
        }

        private void awaitReady() throws Exception {
            long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
            IOException lastFailure = null;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("Embedded Redis exited before readiness: " + boundedLog());
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host(), port), 100);
                    return;
                } catch (IOException exception) {
                    lastFailure = exception;
                    Thread.sleep(25);
                }
            }
            throw new IOException("Embedded Redis readiness timed out: " + boundedLog(), lastFailure);
        }

        @Override
        public void close() throws Exception {
            IOException failure = null;
            if (process.isAlive()) {
                if (!isExactOwnedChild()) {
                    failure = new IOException(
                            "Embedded Redis child identity changed; refusing process control");
                } else {
                    process.destroy();
                    if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        if (!isExactOwnedChild()) {
                            failure = new IOException(
                                    "Embedded Redis child identity changed before forced stop");
                        } else {
                            process.destroyForcibly();
                            if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                                failure = new IOException("Owned embedded Redis child did not stop");
                            }
                        }
                    }
                }
            }
            if (!process.isAlive()) {
                try {
                    deleteOwnedRoot(root);
                } catch (IOException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private boolean isExactOwnedChild() {
            return process.pid() == pid && process.toHandle().info().startInstant()
                    .map(startInstant::equals).orElse(false);
        }

        private String boundedLog() {
            Path log = root.resolve("redis.log");
            if (!Files.exists(log)) {
                return "";
            }
            try (InputStream input = Files.newInputStream(log)) {
                return new String(input.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                return "log unavailable";
            }
        }

        private static Path extractVerifiedBinary(Path root) throws Exception {
            String resource = binaryResource();
            Path binary = root.resolve("redis-server");
            try (InputStream input = RedisVoiceRequestCoordinatorContractTest.class
                    .getResourceAsStream("/" + resource)) {
                if (input == null) {
                    throw new IOException("Embedded Redis 7.4.1 binary resource is unavailable");
                }
                Files.copy(input, binary);
            }
            if (!BINARY_SHA256.get(resource).equals(sha256(binary))) {
                throw new IOException("Embedded Redis 7.4.1 binary checksum mismatch");
            }
            if (!binary.toFile().setExecutable(true, true)) {
                throw new IOException("Embedded Redis 7.4.1 binary is not executable");
            }
            return binary;
        }

        private static String binaryResource() throws IOException {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (!os.contains("linux")) {
                throw new IOException("Embedded Redis 7.4.1 fixture requires Linux CI");
            }
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            String suffix = switch (architecture) {
                case "amd64", "x86_64" -> "amd64";
                case "aarch64", "arm64" -> "arm64";
                default -> throw new IOException(
                        "Embedded Redis 7.4.1 fixture does not support architecture "
                                + architecture);
            };
            return "redis-server-7.4.1-linux-" + suffix;
        }

        private static String sha256(Path file) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(
                    Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private static void requireCompatibleVersion(Path binary) throws Exception {
            Process probe = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true).start();
            if (!probe.waitFor(5, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                probe.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("Embedded Redis version probe timed out");
            }
            String output = new String(
                    probe.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (probe.exitValue() != 0) {
                throw new IOException("Embedded Redis version probe failed");
            }
            Matcher matcher = Pattern.compile("(?:v=)?(\\d+\\.\\d+(?:\\.\\d+)?)")
                    .matcher(output);
            if (!matcher.find()) {
                throw new IOException("Embedded Redis version probe returned no version");
            }
            requireRedis72(matcher.group(1));
        }

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress("127.0.0.1", 0));
                return socket.getLocalPort();
            }
        }

        private static void stopNewChild(Process process) throws Exception {
            if (process == null || !process.isAlive()) {
                return;
            }
            process.destroy();
            if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IOException("New embedded Redis child did not stop");
                }
            }
        }

        private static void deleteOwnedRoot(Path root) throws IOException {
            if (!Files.isRegularFile(root.resolve(OWNER_MARKER))) {
                throw new IOException("Embedded Redis fixture ownership marker missing");
            }
            try (Stream<Path> paths = Files.walk(root)) {
                IOException[] failure = new IOException[1];
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        if (failure[0] == null) {
                            failure[0] = exception;
                        } else {
                            failure[0].addSuppressed(exception);
                        }
                    }
                });
                if (failure[0] != null) {
                    throw failure[0];
                }
            }
        }
    }

}
