package cn.jia.chat.voice.state;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        assertTrue(release.contains("lease ~= ARGV[1]"));
        assertTrue(release.contains("state') == 'IN_PROGRESS'"));
        assertTrue(release.contains("state', 'FAILED_UNKNOWN'"));
        assertTrue(release.indexOf("state', 'FAILED_UNKNOWN'")
                < release.indexOf("ZREM', KEYS[2], ARGV[1]"));
        assertTrue(release.contains("PEXPIRE', KEYS[1], ARGV[2]"));
        assertTrue(release.contains("ZREM', KEYS[2], ARGV[1]"));
        assertEquals(Duration.ofMinutes(10).toMillis(), RedisVoiceRequestCoordinator.SUCCEEDED_TTL_MS);
        assertEquals(Duration.ofMinutes(2).toMillis(), RedisVoiceRequestCoordinator.FAILED_KNOWN_TTL_MS);
        assertEquals(Duration.ofMinutes(10).toMillis(), RedisVoiceRequestCoordinator.FAILED_UNKNOWN_TTL_MS);
    }

    @Test
    void realRedisReleaseAtomicallyTurnsUnterminatedReservationIntoResultUnknown() throws Exception {
        try (OwnedRedisServer server = OwnedRedisServer.start()) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(server.host(), server.port());
            factory.afterPropertiesSet();
            factory.start();
            try {
                VoiceSpeechProperties properties = new VoiceSpeechProperties();
                properties.setCacheEncryptionKey(
                        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
                RedisVoiceRequestCoordinator coordinator = new RedisVoiceRequestCoordinator(
                        factory, properties, new VoicePayloadCipher(properties));
                VoiceBeginResult first = coordinator.begin(
                        VoiceOperation.TRANSCRIPTION, "scope", "request", "digest");
                assertEquals(VoiceBeginResult.Outcome.RESERVED, first.outcome());
                assertScriptLoaded(factory, "BEGIN_SCRIPT");

                coordinator.release(first.reservation());
                assertScriptLoaded(factory, "RELEASE_SCRIPT");

                VoiceBeginResult replay = coordinator.begin(
                        VoiceOperation.TRANSCRIPTION, "scope", "request", "digest");
                assertEquals(VoiceBeginResult.Outcome.RESULT_UNKNOWN, replay.outcome());

                assertConcurrentBeginIsAtomic(coordinator);
            } finally {
                factory.destroy();
            }
        }
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
        private static final String IMAGE_PROPERTY = "cyf.redis.test.image";
        private static final String DEFAULT_IMAGE = "redis:7.2.16-alpine";
        private static final Pattern VERSION = Pattern.compile("(?:v=)?(\\d+)\\.(\\d+)");
        private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
        private final GenericContainer<?> container;
        private final String ownedContainerId;

        private OwnedRedisServer(GenericContainer<?> container) {
            this.container = container;
            this.ownedContainerId = container.getContainerId();
        }

        static OwnedRedisServer start() throws Exception {
            String image = System.getProperty(IMAGE_PROPERTY, DEFAULT_IMAGE);
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no")
                    .withLabel("cyf.test.owner", "JVC-API")
                    .withReuse(false)
                    .withStartupAttempts(1)
                    .withStartupTimeout(START_TIMEOUT)
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(START_TIMEOUT));
            try {
                container.start();
                OwnedRedisServer server = new OwnedRedisServer(container);
                server.requireCompatibleVersion();
                return server;
            } catch (Throwable failure) {
                try {
                    container.stop();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw new IOException("Redis 7.2+ Testcontainer failed; Docker and image "
                        + image + " must be available", failure);
            }
        }

        String host() {
            return container.getHost();
        }

        int port() {
            return container.getMappedPort(6379);
        }

        private void requireCompatibleVersion() throws Exception {
            var result = container.execInContainer("redis-server", "--version");
            Matcher matcher = VERSION.matcher(result.getStdout());
            if (result.getExitCode() != 0 || !matcher.find()) {
                throw new IOException("Redis Testcontainer version probe failed");
            }
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            if (major < 7 || major == 7 && minor < 2) {
                throw new IOException("Redis 4.x/6.x are incompatible; Redis 7.2+ is required");
            }
        }

        @Override
        public void close() throws IOException {
            String currentContainerId = container.getContainerId();
            if (ownedContainerId == null || !ownedContainerId.equals(currentContainerId)) {
                throw new IOException("Redis container identity changed; refusing process control");
            }
            container.stop();
            if (container.isRunning()) {
                throw new IOException("Owned Redis Testcontainer did not stop");
            }
        }
    }

}
