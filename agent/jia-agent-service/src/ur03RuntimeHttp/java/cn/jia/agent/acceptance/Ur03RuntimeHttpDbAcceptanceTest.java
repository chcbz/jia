package cn.jia.agent.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** UR-03 real paired-source socket + production Spring/MyBatis + H2-file acceptance.
 * One serial test owns only its private DB and child Process handles. No Gradle/build/server
 * launches outside Flow, no production credentials, no enrollment/WS/E05/business execution.
 */
class Ur03RuntimeHttpDbAcceptanceTest {
    @TempDir(cleanup = CleanupMode.ALWAYS) Path temporaryDirectory;
    private String stage = "SOURCE_PREPARE";
    private static final long V = Ur03RuntimeHttpFixture.INITIAL_VERSION;

    @Test
    void realRuntimeHttpD06PersistsAcrossApiJvmRestartAndFencesRotatedGeneration() throws Exception {
        Path root = Files.createTempDirectory(temporaryDirectory, "ur03-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
        for (String dir : List.of("home", "tmp", "state")) {
            Files.createDirectory(root.resolve(dir), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Child javaA = null, javaB = null, node = null;
        try {
            Prepared source = prepare(root);
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            String authorization = "rta1_" + HexFormat.of().formatHex(bytes);
            String manifestHash = HexFormat.of().formatHex(Ur03RuntimeHttpFixture.sha256("UR03_SYNTHETIC_MANIFEST_V1"));
            Map<String, Object> javaInput = Map.of("op", "INIT", "root", root.toString(), "initialize", true,
                    "authorization", authorization, "manifestHash", manifestHash);
            stage = "JAVA_A_BOOT";
            javaA = javaChild(root);
            JsonNode readyA = javaA.exchange(javaInput, "JAVA_READY");
            long pidA = readyA.path("pid").asLong(); assertEquals(javaA.process.pid(), pidA);
            String originA = origin(readyA);
            stage = "NODE_BOOT";
            node = nodeChild(source.nodeBin(), root);
            Map<String, Object> manifest = Map.of("runtimeProtocolVersion", "v1", "manifestVersion", "1",
                    "installationId", Ur03RuntimeHttpFixture.INSTALLATION, "tenantId", Ur03RuntimeHttpFixture.TENANT,
                    "clientId", Ur03RuntimeHttpFixture.CLIENT, "canonicalAgentId", Ur03RuntimeHttpFixture.AGENT,
                    "manifestSha256", "sha256:" + manifestHash);
            Map<String, Object> nodeInput = new LinkedHashMap<>();
            nodeInput.put("op", "INIT"); nodeInput.put("clientRoot", source.clientRoot().toString());
            nodeInput.put("clientCommit", source.clientCommit()); nodeInput.put("apiCommit", source.apiCommit());
            nodeInput.put("fixtureRoot", root.toString()); nodeInput.put("stateDir", root.resolve("state").toString());
            nodeInput.put("origin", originA); nodeInput.put("manifest", manifest);
            nodeInput.put("commands", readyA.get("commands")); nodeInput.put("authorization", authorization);
            assertEquals("v20.20.2", node.exchange(nodeInput, "NODE_READY").path("nodeVersion").asText());
            recordStage("NODE_20_20_2_DEFAULT_FETCH", 1);

            stage = "SESSION_1";
            JsonNode session1 = node.exchange(Map.of("op", stage), stage);
            assertEquals(1, session1.path("generation").asLong());
            String digest1 = digest(session1);
            JsonNode initial = inspect(javaA, digest1, 1, "ur03-boot-1", "SENT", V, "SENT", V, 0);
            String fingerprint = initial.path("sourceFingerprint").asText();
            assertTrue(fingerprint.matches("[0-9a-f]{64}"));
            recordStage(stage, 1);

            stage = "RECEIVED";
            node.exchange(Map.of("op", stage, "version", V + 1), stage);
            sameSource(fingerprint, inspect(javaA, digest1, 1, "ur03-boot-1", "RECEIVED", V + 1, "SENT", V, 1));
            recordStage(stage, V + 1);
            stage = "RECEIVED_PRIOR";
            node.exchange(Map.of("op", stage), stage);
            sameSource(fingerprint, inspect(javaA, digest1, 1, "ur03-boot-1", "RECEIVED", V + 1, "SENT", V, 1));
            recordStage(stage, V + 1);
            stage = "STARTED";
            node.exchange(Map.of("op", stage, "version", V + 2), stage);
            sameSource(fingerprint, inspect(javaA, digest1, 1, "ur03-boot-1", "STARTED", V + 2, "SENT", V, 2));
            recordStage(stage, V + 2);

            stage = "JAVA_A_STOP_AND_INDEPENDENT_JDBC";
            javaA.stop();
            // Only AFTER A exits: new connection from the parent JVM opens the persisted H2 file.
            JsonNode persistedA = persisted(root, digest1);
            checkSnapshot(persistedA, digest1, 1, "ur03-boot-1", "STARTED", V + 2, "SENT", V);
            sameSource(fingerprint, persistedA);
            recordStage("INDEPENDENT_JDBC_AFTER_A", V + 2);

            stage = "JAVA_B_BOOT_NO_RESEED";
            javaB = javaChild(root);
            JsonNode readyB = javaB.exchange(Map.of("op", "INIT", "root", root.toString(), "initialize", false,
                    "authorization", authorization, "manifestHash", manifestHash), "JAVA_READY");
            assertEquals(javaB.process.pid(), readyB.path("pid").asLong());
            assertNotEquals(pidA, readyB.path("pid").asLong());
            assertEquals(readyA.get("commands"), readyB.get("commands"));
            stage = "RESTART_PRIOR";
            node.exchange(Map.of("op", stage, "origin", origin(readyB)), stage);
            sameSource(fingerprint, inspect(javaB, digest1, 1, "ur03-boot-1", "STARTED", V + 2, "SENT", V, 0));
            recordStage(stage, V + 2);

            stage = "SESSION_2";
            JsonNode session2 = node.exchange(Map.of("op", stage), stage);
            assertEquals(2, session2.path("generation").asLong());
            String digest2 = digest(session2); assertNotEquals(digest1, digest2);
            sameSource(fingerprint, inspect(javaB, digest2, 2, "ur03-boot-2", "STARTED", V + 2, "SENT", V, 0));
            recordStage(stage, 2);
            stage = "STALE_REJECTED";
            assertEquals(401, node.exchange(Map.of("op", stage), stage).path("status").asInt());
            sameSource(fingerprint, inspect(javaB, digest2, 2, "ur03-boot-2", "STARTED", V + 2, "SENT", V, 0));
            recordStage(stage, 401);

            stage = "FAILED";
            node.exchange(Map.of("op", stage, "version", V + 3), stage);
            sameSource(fingerprint, inspect(javaB, digest2, 2, "ur03-boot-2", "FAILED", V + 3, "SENT", V, 1));
            recordStage(stage, V + 3);
            stage = "FAILED_PRIOR";
            node.exchange(Map.of("op", stage), stage);
            sameSource(fingerprint, inspect(javaB, digest2, 2, "ur03-boot-2", "FAILED", V + 3, "SENT", V, 1));
            recordStage(stage, V + 3);

            stage = "ROLLBACK_REJECTED";
            assertEquals(403, node.exchange(Map.of("op", stage, "version", V + 1), stage).path("status").asInt());
            // Trigger observes the real CAS even though separate JDBC sees its rolled-back row.
            sameSource(fingerprint, inspect(javaB, digest2, 2, "ur03-boot-2", "FAILED", V + 3, "SENT", V, 2));
            recordStage(stage, 403);
            stage = "SECOND_RECEIVED";
            node.exchange(Map.of("op", stage, "version", V + 1), stage);
            sameSource(fingerprint, inspect(javaB, digest2, 2, "ur03-boot-2", "FAILED", V + 3, "RECEIVED", V + 1, 3));
            recordStage(stage, V + 1);

            stage = "STOP_AND_FINAL_INDEPENDENT_JDBC";
            node.stop(); javaB.stop();
            JsonNode persistedB = persisted(root, digest2);
            checkSnapshot(persistedB, digest2, 2, "ur03-boot-2", "FAILED", V + 3, "RECEIVED", V + 1);
            sameSource(fingerprint, persistedB);
            recordStage("FINAL_INDEPENDENT_JDBC_H2_FILE", 2);
        } catch (SafeFailure failure) {
            throw new AssertionError("UR03_" + stage + " " + failure.safe);
        } catch (Throwable ignored) {
            // Assertion/driver/framework messages may contain source paths, SQL parameters or tokens.
            throw new AssertionError("UR03_" + stage + " UR03_ASSERTION_OR_HARNESS_FAILURE");
        } finally {
            if (node != null) node.close();
            if (javaB != null) javaB.close();
            if (javaA != null) javaA.close();
        }
    }

    private static JsonNode inspect(Child java, String digest, long generation, String boot,
            String firstStatus, long firstVersion, String secondStatus, long secondVersion, long updates) throws Exception {
        JsonNode snapshot = java.exchange(Map.of("op", "SNAPSHOT", "tokenDigest", digest), "SNAPSHOT");
        checkSnapshot(snapshot, digest, generation, boot, firstStatus, firstVersion, secondStatus, secondVersion);
        assertEquals(updates, snapshot.path("updates").asLong());
        return snapshot;
    }
    private static void checkSnapshot(JsonNode snapshot, String digest, long generation, String boot,
            String firstStatus, long firstVersion, String secondStatus, long secondVersion) {
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertEquals(generation, snapshot.path("generation").asLong());
        assertEquals(boot, snapshot.path("boot").asText());
        assertTrue(snapshot.path("digestOnly").asBoolean()); assertTrue(snapshot.path("digestMatches").asBoolean());
        assertTrue(snapshot.path("scopeMatches").asBoolean());
        assertEquals(1, snapshot.path("runtimeCount").asInt()); assertEquals(1, snapshot.path("installationCount").asInt());
        assertEquals(2, snapshot.path("outboxCount").asInt()); assertEquals(2, snapshot.path("inboxCount").asInt());
        assertEquals(2, snapshot.path("deliveries").size());
        assertEquals(firstStatus, snapshot.path("deliveries").get(0).path("status").asText());
        assertEquals(firstVersion, snapshot.path("deliveries").get(0).path("version").asLong());
        assertEquals(secondStatus, snapshot.path("deliveries").get(1).path("status").asText());
        assertEquals(secondVersion, snapshot.path("deliveries").get(1).path("version").asLong());
    }
    private static void sameSource(String fingerprint, JsonNode snapshot) {
        assertEquals(fingerprint, snapshot.path("sourceFingerprint").asText());
    }
    private static JsonNode persisted(Path root, String digest) throws Exception {
        JdbcTemplate independentJdbc = new JdbcTemplate(Ur03RuntimeHttpFixture.dataSource(root, true));
        return Ur03RuntimeHttpFixture.JSON.valueToTree(Ur03RuntimeHttpFixture.snapshot(independentJdbc, digest));
    }
    private static String digest(JsonNode session) {
        String value = session.path("tokenDigest").asText();
        assertTrue(value.matches("[0-9a-f]{64}")); return value;
    }
    private static String origin(JsonNode ready) {
        int port = ready.path("port").asInt(); assertTrue(port > 0 && port <= 65535);
        return "http://127.0.0.1:" + port;
    }
    private static void recordStage(String stage, long number) {
        // No public command body, raw assertion, DB path, token digest or credential in test output.
        System.out.println("UR03_H2_HTTP " + stage + " " + number);
    }

    private static Prepared prepare(Path root) throws Exception {
        String apiCommit = requiredEnv("UR03_API_COMMIT"), clientCommit = requiredEnv("UR03_CLIENT_COMMIT");
        if (!apiCommit.matches("[0-9a-f]{40}") || !clientCommit.matches("[0-9a-f]{40}")) {
            throw new SafeFailure("UR03_FULL_COMMIT_REQUIRED");
        }
        Path apiRoot = Path.of(requiredProperty("ur03.api.root")).toRealPath();
        Path requestedClientRoot = Path.of(requiredEnv("UR03_CLIENT_ROOT"));
        Path clientRoot = requestedClientRoot.toRealPath();
        if (!requestedClientRoot.isAbsolute() || !requestedClientRoot.normalize().equals(clientRoot)) {
            throw new SafeFailure("UR03_CLIENT_SOURCE_ALIAS_FORBIDDEN");
        }
        Path nodeBin = Path.of(requiredEnv("UR03_NODE_BIN")).toRealPath();
        if (!nodeBin.isAbsolute() || !Files.isExecutable(nodeBin) || apiRoot.equals(clientRoot)
                || root.startsWith(clientRoot) || root.startsWith(apiRoot)) throw new SafeFailure("UR03_ISOLATED_SOURCE_REQUIRED");
        if (!git(root, apiRoot, "rev-parse", "HEAD").equals(apiCommit)) {
            throw new SafeFailure("UR03_PAIRED_COMMIT_MISMATCH");
        }
        // Read-only tracked-byte verification, not a checkout/fetch or credential/config read.
        git(root, apiRoot, "diff", "--exit-code", "--quiet", "HEAD", "--");
        // Controller pins archiveSHA/manifestSHA in Flow before extraction. This fixture verifies
        // that the sole archive manifest describes exactly the two imported, non-aliased bytes.
        // There is deliberately no client .git requirement or alternate checkout proof format.
        String clientTree = verifyClientArchive(clientRoot, clientCommit);
        requiredProperty("ur03.fixture.classpath");
        String apiTree = git(root, apiRoot, "rev-parse", "HEAD^{tree}");
        if (!apiTree.matches("[0-9a-f]{40}") || !clientTree.matches("[0-9a-f]{40}")) throw new SafeFailure("UR03_SOURCE_TREE_REQUIRED");
        // Public immutable provenance only. Not an environment/configuration dump.
        System.out.println("UR03_PAIRED_SOURCE api=" + apiCommit + " tree=" + apiTree
                + " client=" + clientCommit + " tree=" + clientTree
                + " clientSource=git-archive apiTrackedDiff=verified requiredNode=20.20.2 fixture=HTTP_SPRING_MYBATIS_H2_FILE");
        return new Prepared(clientRoot, nodeBin, apiCommit, clientCommit);
    }
    private static String verifyClientArchive(Path clientRoot, String commit) throws Exception {
        List<String> modules = List.of("conf/cyf-agent-runtime-v1/lib/runtime-client.mjs",
                "conf/cyf-agent-runtime-v1/lib/security.mjs");
        Path manifestPath = archiveFile(clientRoot, "ur03-client-source.json");
        JsonNode manifest = Ur03RuntimeHttpFixture.JSON.readTree(Files.readAllBytes(manifestPath));
        if (manifest == null || !manifest.isObject() || manifest.size() != 4
                || !manifest.path("format").isTextual() || !manifest.path("commit").isTextual()
                || !manifest.path("tree").isTextual()
                || !"ur03-git-archive-v1".equals(manifest.path("format").asText())
                || !commit.equals(manifest.path("commit").asText())
                || !manifest.path("tree").asText().matches("[0-9a-f]{40}")) {
            throw new SafeFailure("UR03_CLIENT_ARCHIVE_MANIFEST_INVALID");
        }
        JsonNode files = manifest.path("files");
        if (!files.isObject() || files.size() != modules.size()) {
            throw new SafeFailure("UR03_CLIENT_ARCHIVE_FILES_INVALID");
        }
        Set<Path> allowed = new HashSet<>();
        allowed.add(manifestPath);
        allowed.add(clientRoot);
        for (String module : modules) {
            String expectedHash = files.path(module).asText();
            if (!files.path(module).isTextual() || !expectedHash.matches("[0-9a-f]{64}")) {
                throw new SafeFailure("UR03_CLIENT_ARCHIVE_HASH_REQUIRED");
            }
            Path file = archiveFile(clientRoot, module);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            if (!hash.equals(expectedHash)) throw new SafeFailure("UR03_CLIENT_ARCHIVE_HASH_MISMATCH");
            for (Path ancestor = file; ancestor.startsWith(clientRoot); ancestor = ancestor.getParent()) {
                allowed.add(ancestor);
            }
        }
        // Reject extra source files/.git, symlinks, directory aliases and hard-linked modules.
        try (var paths = Files.walk(clientRoot)) {
            for (Path path : paths.toList()) {
                if (!allowed.contains(path) || Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) {
                    throw new SafeFailure("UR03_CLIENT_ARCHIVE_CONTENT_INVALID");
                }
            }
        }
        return manifest.path("tree").asText();
    }
    private static Path archiveFile(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        for (Path ancestor = file; ancestor.startsWith(root); ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) throw new SafeFailure("UR03_CLIENT_SOURCE_ALIAS_FORBIDDEN");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)
                || ((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
            throw new SafeFailure("UR03_CLIENT_SOURCE_ALIAS_FORBIDDEN");
        }
        return file;
    }
    private record Prepared(Path clientRoot, Path nodeBin, String apiCommit, String clientCommit) { }
    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new SafeFailure("UR03_PAIRED_ENV_REQUIRED"); return value;
    }
    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new SafeFailure("UR03_TEST_TASK_REQUIRED"); return value;
    }

    private static ProcessBuilder isolated(Path root, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile());
        Map<String, String> environment = builder.environment(); environment.clear();
        environment.put("PATH", "/usr/bin:/bin"); environment.put("HOME", root.resolve("home").toString());
        environment.put("TMPDIR", root.resolve("tmp").toString()); environment.put("TZ", "UTC"); environment.put("LANG", "C.UTF-8");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder;
    }
    private static String git(Path root, Path checkout, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/usr/bin/git", "-C", checkout.toString()));
        command.addAll(List.of(args));
        Process process = isolated(root, command).start();
        try {
            // Commands produce a commit/tree or no output, never credentials/config contents.
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) throw new SafeFailure("UR03_GIT_SOURCE_CHECK_FAILED");
            return output;
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
    }
    private static Child javaChild(Path root) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        return new Child(isolated(root, List.of(java.toString(), "-Djava.io.tmpdir=" + root.resolve("tmp"),
                "-Duser.home=" + root.resolve("home"), "-Duser.timezone=UTC", "-cp", requiredProperty("ur03.fixture.classpath"),
                Ur03RuntimeHttpFixture.class.getName())).start(), true);
    }
    private static Child nodeChild(Path node, Path root) throws Exception {
        Path script = root.resolve("runtime-v1-http-client.mjs");
        try (InputStream resource = Ur03RuntimeHttpDbAcceptanceTest.class.getResourceAsStream("/ur03/runtime-v1-http-client.mjs")) {
            if (resource == null) throw new SafeFailure("UR03_NODE_RESOURCE_REQUIRED");
            Files.copy(resource, script);
        }
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rw-------"));
        return new Child(isolated(root, List.of(node.toString(), script.toString())).start(), false);
    }

    private static final class Child implements AutoCloseable {
        final Process process;
        final boolean java;
        final BufferedReader output;
        final BufferedWriter input;
        final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ur03-owned-pipe"); thread.setDaemon(true); return thread;
        });
        Child(Process process, boolean java) {
            this.process = process; this.java = java;
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }
        JsonNode exchange(Object request, String expectedStage) throws Exception {
            input.write(Ur03RuntimeHttpFixture.JSON.writeValueAsString(request)); input.newLine(); input.flush();
            Future<JsonNode> next = reader.submit(() -> {
                String line;
                while ((line = output.readLine()) != null) {
                    if (java) {
                        if (!line.startsWith(Ur03RuntimeHttpFixture.PREFIX)) continue;
                        line = line.substring(Ur03RuntimeHttpFixture.PREFIX.length());
                    }
                    return Ur03RuntimeHttpFixture.JSON.readTree(line);
                }
                throw new SafeFailure("UR03_CHILD_EXIT_WITHOUT_RECEIPT");
            });
            JsonNode receipt;
            try { receipt = next.get(120, TimeUnit.SECONDS); }
            catch (TimeoutException timeout) { next.cancel(true); throw new SafeFailure("UR03_FIXTURE_CONTROL_WAIT_EXPIRED"); }
            catch (ExecutionException ignored) { throw new SafeFailure("UR03_CHILD_PROTOCOL_FAILURE"); }
            if ("ERROR".equals(receipt.path("stage").asText())) {
                StringBuilder diagnostic = new StringBuilder();
                for (String key : List.of("at", "code", "runtimeCode", "type", "sqlState")) {
                    String safe = receipt.path(key).asText();
                    if (safe.matches("[A-Za-z0-9_]{1,80}")) diagnostic.append(key).append('=').append(safe).append(' ');
                }
                int status = receipt.path("status").asInt();
                if (status >= 100 && status <= 599) diagnostic.append("status=").append(status);
                throw new SafeFailure("UR03_CHILD_REJECTED " + diagnostic);
            }
            if (!expectedStage.equals(receipt.path("stage").asText())) throw new SafeFailure("UR03_CHILD_STAGE_MISMATCH");
            return receipt;
        }
        void stop() throws Exception {
            exchange(Map.of("op", "STOP"), "STOPPED"); input.close();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) throw new SafeFailure("UR03_CHILD_STOP_FAILED");
        }
        @Override public void close() {
            // Only directly owned children. Never enumerate, pkill, cancel Flow or stop a service.
            try {
                if (process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            finally { reader.shutdownNow(); try { input.close(); output.close(); } catch (IOException ignored) { } }
        }
    }
    private static final class SafeFailure extends RuntimeException {
        final String safe;
        SafeFailure(String safe) { super(safe, null, false, false); this.safe = safe; }
    }
}
