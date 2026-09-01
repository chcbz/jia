package cn.jia.chat.voice.state;

import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.File;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
            LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", server.port());
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

                coordinator.release(first.reservation());

                VoiceBeginResult replay = coordinator.begin(
                        VoiceOperation.TRANSCRIPTION, "scope", "request", "digest");
                assertEquals(VoiceBeginResult.Outcome.RESULT_UNKNOWN, replay.outcome());
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

    private static final class OwnedRedisServer implements AutoCloseable {
        private static final String OWNER_MARKER = "JVC-API-OWNED";
        private static final Pattern VERSION = Pattern.compile("(?:v=)?(\\d+)\\.(\\d+)");
        private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
        private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
        private final Path root;
        private final Process process;
        private final long pid;
        private final Instant startInstant;
        private final int port;

        private OwnedRedisServer(Path root, Process process, int port) throws IOException {
            this.root = root;
            this.process = process;
            this.pid = process.pid();
            this.startInstant = process.toHandle().info().startInstant()
                    .orElseThrow(() -> new IOException("Redis child start identity unavailable"));
            this.port = port;
        }

        static OwnedRedisServer start() throws Exception {
            Path binary = locateBinary();
            requireLuaCapableVersion(binary);
            Path root = Files.createTempDirectory("jvc-api-redis-");
            Files.writeString(root.resolve(OWNER_MARKER), "owned\n",
                    StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            Files.createDirectory(root.resolve("tmp"));
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
                    """, port, root, root.resolve("redis.pid")), StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW);

            ProcessBuilder builder = new ProcessBuilder(binary.toString(), config.toString());
            builder.directory(root.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(root.resolve("redis.log").toFile());
            builder.environment().clear();
            builder.environment().put("PATH", System.getenv().getOrDefault(
                    "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
            builder.environment().put("LANG", "C");
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("HOME", root.toString());
            builder.environment().put("TMPDIR", root.resolve("tmp").toString());
            Process process = builder.start();
            OwnedRedisServer server = new OwnedRedisServer(root, process, port);
            try {
                server.awaitReady();
                return server;
            } catch (Throwable failure) {
                try {
                    server.close();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        int port() {
            return port;
        }

        private void awaitReady() throws Exception {
            long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
            IOException lastFailure = null;
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("Redis child exited before readiness: " + boundedLog());
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                    return;
                } catch (IOException exception) {
                    lastFailure = exception;
                    Thread.sleep(25);
                }
            }
            throw new IOException("Redis child readiness timed out: " + boundedLog(), lastFailure);
        }

        @Override
        public void close() throws Exception {
            IOException failure = null;
            if (process.isAlive()) {
                if (!isExactOwnedChild()) {
                    failure = new IOException("Redis child identity changed; refusing process control");
                } else {
                    process.destroy();
                    if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                        if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                            failure = new IOException("Owned Redis child did not stop");
                        }
                    }
                }
            }
            if (!process.isAlive()) {
                try {
                    deleteOwnedRoot();
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

        private void deleteOwnedRoot() throws IOException {
            if (!Files.isRegularFile(root.resolve(OWNER_MARKER))) {
                throw new IOException("Redis fixture ownership marker missing");
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

        private static Path locateBinary() throws IOException {
            List<Path> candidates = new ArrayList<>();
            addConfigured(candidates, System.getProperty("cyf.redis.server.binary"));
            addConfigured(candidates, System.getenv("CYF_REDIS_SERVER_BINARY"));
            candidates.add(Path.of("/home/isp/apps/redis/bin/redis-server"));
            candidates.add(Path.of("/usr/local/bin/redis-server"));
            candidates.add(Path.of("/usr/bin/redis-server"));
            String path = System.getenv("PATH");
            if (path != null) {
                for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
                    if (!directory.isBlank()) {
                        candidates.add(Path.of(directory, "redis-server"));
                    }
                }
            }
            for (Path candidate : candidates) {
                Path absolute = candidate.toAbsolutePath().normalize();
                if (Files.isRegularFile(absolute) && Files.isExecutable(absolute)) {
                    return absolute;
                }
            }
            throw new IOException("Lua-capable redis-server binary is required for this contract test");
        }

        private static void addConfigured(List<Path> candidates, String configured) {
            if (configured != null && !configured.isBlank()) {
                candidates.add(Path.of(configured));
            }
        }

        private static void requireLuaCapableVersion(Path binary) throws Exception {
            Process probe = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true).start();
            if (!probe.waitFor(5, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                probe.waitFor(5, TimeUnit.SECONDS);
                throw new IOException("redis-server version probe timed out");
            }
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            if (probe.exitValue() != 0) {
                throw new IOException("redis-server version probe failed");
            }
            Matcher matcher = VERSION.matcher(output);
            if (!matcher.find() || Integer.parseInt(matcher.group(1)) < 4) {
                throw new IOException("redis-server 4+ with Lua/EVALSHA is required");
            }
        }

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress("127.0.0.1", 0));
                return socket.getLocalPort();
            }
        }
    }
}
