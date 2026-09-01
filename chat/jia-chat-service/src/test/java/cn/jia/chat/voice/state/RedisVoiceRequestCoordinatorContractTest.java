package cn.jia.chat.voice.state;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
                assertRedisRuntimeCapabilities(factory);
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
