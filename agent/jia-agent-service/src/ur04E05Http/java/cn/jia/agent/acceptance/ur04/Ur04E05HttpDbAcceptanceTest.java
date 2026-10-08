package cn.jia.agent.acceptance.ur04;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit local opt-in only. Six complete E05 original-engine/real-HTTP/native-WS/DB selectors.
 * No mocks, Provider, production services, installer or shared DB. Synthetic historical
 * assignment/transport + nonpaid child are explicitly NOT actual model/MySQL/enroll proof. */
class Ur04E05HttpDbAcceptanceTest {
    @TempDir(cleanup = CleanupMode.ALWAYS) Path temporaryDirectory;
    private static final String BASELINE = "4c5875b5bed143c793d7812143d60deab8d65674";
    private static final String CLIENT = "7594fd72251d38b6e1d23a1a3cca184ae0d085e7";
    private String stage = "SOURCE_PREPARE";

    @Test void e05ActualClientHttpCommitsOriginalResultAndTerminalAck() throws Exception {
        safe(() -> { try (Lane f = new Lane("NONE")) {
            JsonNode command = f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
            f.terminal(0); JsonNode before = f.serverSnapshot(); assertCommitted(before, 0, 1);
            assertD06Sequence(before, 0); assertEquals(1, children(f.nodeSnapshot(), 0));
            JsonNode original = f.node.exchange(Map.of("op", "ORIGINAL_READBACK", "agentIndex", 0, "commandId", text(command, "commandId")), "ORIGINAL_READBACK");
            assertTrue(bool(original, "completed")); assertTrue(text(original, "materialDigest").matches("[0-9a-f]{64}"));
            JsonNode after = f.serverSnapshot(); assertEquals(text(before, "businessSha256"), text(after, "businessSha256"));
            assertTrue(http(after).stream().anyMatch(r -> "RESULT".equals(optional(r, "category")) && "GET".equals(optional(r, "method")) && bool(r, "readOnly")));
            JsonNode duplicate = f.node.exchange(Map.of("op", "DUPLICATE_ACK", "agentIndex", 0, "commandId", text(command, "commandId")), "DUPLICATE_ACK");
            assertEquals("PRIOR", text(duplicate, "kind")); assertEquals(10, integer(duplicate, "deliveryVersion"));
            f.dispatch(0); assertTrue(bool(f.java.exchange(Map.of("op", "ALTERED_DISPATCH", "agentIndex", 0), "DISPATCHED"), "sent"));
            JsonNode conflictObserved = f.waitNode(n -> array(agent(n, 0), "conflicts").size() == 1 && array(agent(n, 0), "pending").isEmpty());
            JsonNode conflict = array(agent(conflictObserved, 0), "conflicts").getFirst();
            assertEquals(text(command, "commandId"), text(conflict, "commandId"));
            assertNotEquals(text(conflict, "existingFingerprint"), text(conflict, "conflictingFingerprint"));
            assertEquals(text(array(agent(conflictObserved, 0), "ledger").getFirst(), "fingerprint"), text(conflict, "existingFingerprint"));
            JsonNode finalState = f.serverSnapshot(); assertEquals(integer(before, "ackUpdates"), integer(finalState, "ackUpdates"));
            assertEquals(text(before, "businessSha256"), text(finalState, "businessSha256")); assertEquals(1, children(f.nodeSnapshot(), 0));
            assertUniqueCheckpoint(f.nodeSnapshot(), 0, text(command, "commandId"));
            f.stopBoth(); assertCommitted(f.persisted(), 0, 1);
        }});
    }

    @Test void e05LostResultReadbackSurvivesJavaAndNodeRestart() throws Exception {
        safe(() -> { try (Lane f = new Lane("RESULT_LOSS")) {
            f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
            JsonNode held = f.java.receipt("RESULT_COMMIT_HELD"); assertCommittedBusiness(held.get("snapshot"), 0, 1);
            long javaA = f.java.process.pid(), nodeA = f.node.process.pid(); f.java.crash();
            JsonNode recovery = f.waitNode(s -> hasInbox(s, 0, "recovery_required", true));
            assertTrue(bool(agent(recovery, 0), "credentialsAbsent")); assertEquals(1, children(recovery, 0));
            f.node.stop("NODE_STOPPED"); JsonNode persisted = f.persisted(); assertCommittedBusiness(persisted, 0, 1);
            assertEquals(9, integer(persisted.get("deliveries").get(0), "version"));
            String business = text(persisted, "businessSha256"); f.restart(); assertNotEquals(javaA, f.java.process.pid()); assertNotEquals(nodeA, f.node.process.pid());
            f.terminal(0); JsonNode result = f.serverSnapshot(); assertCommitted(result, 0, 1);
            assertEquals(business, text(result, "businessSha256")); assertEquals(1, children(f.nodeSnapshot(), 0));
            assertRecoveryOnly(result, false); assertEquals(2, integer(result.get("runtimes").get(0), "generation"));
            f.stopBoth(); assertEquals(business, text(f.persisted(), "businessSha256"));
        }});
    }

    @Test void e05TerminalAckLostResponseReplaysPriorWithoutWorkRerun() throws Exception {
        safe(() -> { try (Lane f = new Lane("ACK_LOSS")) {
            f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
            JsonNode held = f.java.receipt("TERMINAL_ACK_HELD"); assertCommitted(held.get("snapshot"), 0, 1);
            f.java.crash(); JsonNode pending = f.waitNode(n -> !array(agent(n, 0), "pending").isEmpty());
            assertTrue(array(agent(pending, 0), "pending").size() >= 1); assertEquals(1, children(pending, 0));
            f.node.stop("NODE_STOPPED"); JsonNode original = f.persisted(); String business = text(original, "businessSha256");
            long oldNode = f.node.process.pid(), oldJava = f.java.process.pid(); f.restart();
            assertNotEquals(oldNode, f.node.process.pid()); assertNotEquals(oldJava, f.java.process.pid()); f.terminal(0);
            JsonNode recovered = f.serverSnapshot(); assertCommitted(recovered, 0, 1); assertRecoveryOnly(recovered, true);
            assertEquals(0, integer(recovered, "ackUpdates")); assertEquals(business, text(recovered, "businessSha256"));
            assertEquals(1, children(f.nodeSnapshot(), 0)); assertUniqueCheckpoint(f.nodeSnapshot(), 0, text(f.commands.get(0), "commandId"));
            f.stopBoth(); assertEquals(business, text(f.persisted(), "businessSha256"));
        }});
    }

    @Test void e05PreparedCommitRefencesAndRollbackIsDurable() throws Exception {
        safe(() -> {
            try (Lane f = new Lane("PREPARED")) {
                f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0); f.java.receipt("PREPARED_HELD");
                JsonNode prior = f.serverSnapshot(); assertEquals(0, integer(prior, "artifacts")); assertEquals(0, integer(prior, "submittedEvents"));
                assertEquals(2, integer(f.node.exchange(Map.of("op", "ROTATE", "agentIndex", 0), "ROTATED"), "generation"));
                f.java.exchange(Map.of("op", "RELEASE_PREPARED"), "PREPARED_RELEASED");
                f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); JsonNode denied = f.serverSnapshot();
                assertEquals(0, integer(denied, "artifacts")); assertEquals(0, integer(denied, "submittedEvents"));
                assertTrue(http(denied).stream().anyMatch(r -> "RESULT".equals(optional(r, "category")) && "POST".equals(optional(r, "method")) && integer(r, "status") == 401));
                f.stopBoth(); JsonNode durable = f.persisted(); assertEquals(0, integer(durable, "artifacts")); assertEquals(0, integer(durable, "submittedEvents"));
            }
            try (Lane f = new Lane("ROLLBACK")) {
                f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0);
                f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); JsonNode rolled = f.serverSnapshot();
                assertTrue(integer(rolled, "artifactInserts") >= 1); assertTrue(integer(rolled, "submittedAttempts") >= 1); assertTrue(integer(rolled, "workUpdates") >= 3);
                assertEquals(0, integer(rolled, "artifacts")); assertEquals(0, integer(rolled, "submittedEvents"));
                assertEquals(integer(rolled, "events"), integer(rolled, "eventVersion")); assertEquals("running", text(rolled.get("work").get(0), "status"));
                assertFalse(bool(rolled.get("work").get(0), "hasResult")); assertNoSucceeded(rolled);
                JsonNode notFound = f.node.exchange(Map.of("op", "HTTP", "agentIndex", 0, "method", "GET", "path", resultPath(f.commands.get(0))), "HTTP_RESULT");
                assertEquals(404, integer(notFound, "status")); f.stopBoth(); JsonNode persisted = f.persisted();
                assertEquals(text(rolled, "businessSha256"), text(persisted, "businessSha256")); assertEquals(0, integer(persisted, "artifacts"));
                f.restart(); JsonNode recovery = f.nodeSnapshot(); assertTrue(hasInbox(recovery, 0, "recovery_required", true));
                assertEquals(1, children(recovery, 0)); assertRecoveryOnly(f.serverSnapshot(), false);
            }
        });
    }

    @Test void e05SourceAndOriginalResultProofStayFailClosed() throws Exception {
        safe(() -> {
            for (String mutation : List.of("SUCCESSOR_HASH", "PREDECESSOR_HASH", "SOURCE_TARGET")) {
                try (Lane f = new Lane("NONE")) {
                    f.seed(0); f.dispatch(0); f.awaitRealHeartbeat(0);
                    // D06 independently validates canonical source. Corrupt only after its real
                    // STARTED and lease/heartbeat, to isolate original result-source final proof.
                    f.mutate(0, mutation);
                    f.node.exchange(Map.of("op", "RELEASE", "agentIndex", 0), "CHILD_RELEASED");
                    f.waitNode(s -> hasInbox(s, 0, "recovery_required", true));
                    JsonNode rejected = f.serverSnapshot(); assertEquals(0, integer(rejected, "artifacts")); assertEquals(0, integer(rejected, "submittedEvents")); assertNoSucceeded(rejected);
                    long expectedStatus = mutation.equals("SOURCE_TARGET") ? 404 : 409;
                    assertTrue(http(rejected).stream().anyMatch(r -> "RESULT".equals(optional(r, "category")) && expectedStatus == integer(r, "status")));
                    f.stopBoth(); assertEquals(0, integer(f.persisted(), "artifacts"));
                }
            }
            for (String mutation : List.of("PROOF", "MATERIAL")) {
                try (Lane f = new Lane("RESULT_LOSS")) {
                    f.seed(0); f.dispatch(0); f.releaseAfterRealHeartbeat(0); f.java.receipt("RESULT_COMMIT_HELD");
                    // Corrupt actual committed proof/material before dropping the real response.
                    f.mutate(0, mutation); f.java.crash(); f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); f.node.stop("NODE_STOPPED");
                    String fingerprint = text(f.persisted(), "businessSha256"); f.restart();
                    f.node.exchange(Map.of("op", "RECOVER", "agentIndex", 0), "RECOVERY_OBSERVED");
                    assertTrue(hasInbox(f.nodeSnapshot(), 0, "recovery_required", true)); assertNoSucceeded(f.serverSnapshot());
                    assertEquals(fingerprint, text(f.serverSnapshot(), "businessSha256")); assertRecoveryOnly(f.serverSnapshot(), false); assertEquals(1, children(f.nodeSnapshot(), 0));
                }
            }
            for (String variant : List.of("ACTOR", "SCOPE", "OLD_SESSION", "UNREGISTERED", "PRODUCER")) {
                try (Lane f = new Lane("ROLLBACK")) {
                    JsonNode command = f.seed(0);
                    if (variant.equals("PRODUCER")) { f.dispatch(0); f.releaseAfterRealHeartbeat(0); f.waitNode(s -> hasInbox(s, 0, "recovery_required", true)); }
                    JsonNode prior = f.serverSnapshot();
                    JsonNode denied = f.node.exchange(Map.of("op", "NEGATIVE_HTTP", "agentIndex", 0, "variant", variant, "command", command), "NEGATIVE_HTTP");
                    assertEquals(Set.of("ACTOR", "PRODUCER").contains(variant) ? 403 : 401, integer(denied, "status"));
                    assertEquals(text(prior, "businessSha256"), text(f.serverSnapshot(), "businessSha256")); assertNoSucceeded(f.serverSnapshot());
                }
            }
        });
    }

    @Test void e05UnknownStartedNeverRerunsAndSiblingRemainsReady() throws Exception {
        safe(() -> { try (Lane f = new Lane("NONE")) {
            JsonNode original = f.seed(0); f.dispatch(0);
            JsonNode running = f.waitNode(s -> children(s, 0) == 1); f.trackChildren(running);
            JsonNode before = f.serverSnapshot(); assertEquals("STARTED", text(before.get("deliveries").get(0), "status"));
            assertFalse(hasInbox(running, 0, "processing", true)); f.node.crash(); f.java.stop("STOPPED");
            JsonNode durable = f.persisted(); assertEquals(0, integer(durable, "artifacts")); assertEquals(0, integer(durable, "submittedEvents"));
            f.restart(); JsonNode unknown = f.nodeSnapshot(); assertTrue(hasInbox(unknown, 0, "recovery_required", false)); assertEquals(1, children(unknown, 0));
            f.node.exchange(Map.of("op", "RECOVER", "agentIndex", 0), "RECOVERY_OBSERVED");
            assertEquals(1, children(f.nodeSnapshot(), 0)); assertNoSucceeded(f.serverSnapshot());
            assertTrue(bool(agent(f.nodeSnapshot(), 1), "ready")); assertTrue(bool(agent(f.nodeSnapshot(), 2), "ready"));
            f.mutate(0, "REVOKE");
            JsonNode revoked = f.node.exchange(Map.of("op", "HTTP", "agentIndex", 0, "method", "GET", "path", resultPath(original)), "HTTP_RESULT");
            assertEquals(401, integer(revoked, "status"));
            // Exercise original reconnect/session rejection after the actual private DB revocation.
            f.node.exchange(Map.of("op", "DISCONNECT", "agentIndex", 0), "SUBJECT_DISCONNECTED");
            JsonNode isolatedSubject = f.waitNode(n -> bool(agent(n, 0), "isolated"));
            assertFalse(bool(agent(isolatedSubject, 0), "ready"));
            f.seed(1); f.dispatch(1); f.releaseAfterRealHeartbeat(1); f.terminal(1);
            JsonNode isolated = f.serverSnapshot(); assertEquals(1, integer(isolated, "artifacts")); assertEquals(1, integer(isolated, "submittedEvents"));
            assertEquals("SUCCEEDED", text(isolated.get("deliveries").get(1), "status")); assertNotEquals("SUCCEEDED", text(isolated.get("deliveries").get(0), "status"));
            JsonNode node = f.nodeSnapshot(); assertEquals(1, children(node, 0)); assertEquals(1, children(node, 1)); assertEquals(0, children(node, 2));
            for (JsonNode a : array(node, "agents")) assertTrue(bool(a, "credentialsAbsent"));
            assertTrue(bool(agent(node, 2), "ready")); f.trackChildren(node);
        }});
    }

    private interface Checked { void run() throws Exception; }
    private void safe(Checked action) throws Exception {
        try { verifyOptionalDiagnostics(); action.run(); }
        catch (SafeFailure e) { throw new AssertionError("UR04_" + stage + " " + e.code); }
        catch (Throwable e) { throw new AssertionError("UR04_" + stage + " " + Ur04E05HttpFixture.safeFailure(e)); }
    }
    private static void verifyOptionalDiagnostics() {
        // Same six acceptance selectors run this small safety guard before child startup.
        // It is not evidence of Java Boot or business HTTP success.
        JsonNode absent = Ur04E05HttpFixture.JSON.valueToTree(Map.of("stage", "ERROR", "code", "UR04_JAVA_FAILURE", "at", "BOOT_SCHEMA"));
        String minimal = Child.errorFailure(absent).code;
        if (!minimal.contains("at=BOOT_SCHEMA") || !minimal.contains("UR04_JAVA_FAILURE")) throw new SafeFailure("OPTIONAL_DIAGNOSTIC_STAGE_LOST");
        JsonNode untrusted = Ur04E05HttpFixture.JSON.valueToTree(Map.of("stage", "ERROR", "code", "UR04_JAVA_FAILURE",
            "type", "UR04_SYNTHETIC_REDACTION_PROBE", "bean", "UR04_SYNTHETIC_REDACTION_PROBE", "errno", "UR04_SYNTHETIC_REDACTION_PROBE",
            "missingClass", "UR04_SYNTHETIC_REDACTION_PROBE"));
        if (Child.errorFailure(untrusted).code.contains("REDACTION_PROBE")) throw new SafeFailure("OPTIONAL_DIAGNOSTIC_ALLOWLIST_FAILED");
        JsonNode allowed = Ur04E05HttpFixture.JSON.valueToTree(Map.of("stage", "ERROR", "code", "UR04_JAVA_FAILURE", "bean", "sqlSessionFactory", "errno", 2));
        String diagnostic = Child.errorFailure(allowed).code;
        if (!diagnostic.contains("bean=sqlSessionFactory") || !diagnostic.contains("errno=2")) throw new SafeFailure("OPTIONAL_DIAGNOSTIC_NUMBER_LOST");
    }
    private static String text(JsonNode n, String key) { return Ur04E05HttpFixture.text(n, key); }
    private static long integer(JsonNode n, String key) { return Ur04E05HttpFixture.integer(n, key); }
    private static boolean bool(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); if (value == null || !value.isBoolean()) throw new SafeFailure("BUSINESS_BOOLEAN_REQUIRED"); return value.asBoolean();
    }
    private static List<JsonNode> array(JsonNode n, String key) {
        JsonNode value = n == null ? null : n.get(key); if (value == null || !value.isArray()) throw new SafeFailure("BUSINESS_ARRAY_REQUIRED");
        List<JsonNode> result = new ArrayList<>(); value.forEach(result::add); return result;
    }
    // Optional diagnostics only: never MissingNode.asText()/asInt() with no default.
    private static String optional(JsonNode n, String key) { return n == null ? "" : Ur04E05HttpFixture.optionalText(n, key); }
    private static JsonNode agent(JsonNode s, int index) { return array(s, "agents").get(index); }
    private static int children(JsonNode s, int index) { return array(agent(s, index), "children").size(); }
    private static boolean hasInbox(JsonNode s, int index, String state, boolean material) {
        return array(agent(s, index), "inbox").stream().anyMatch(r -> state.equals(optional(r, "state")) && (!material || optional(r, "materialDigest").matches("[0-9a-f]{64}")));
    }
    private static boolean hasTerminalFailure(JsonNode s, int index) {
        return array(agent(s, index), "ledger").stream().anyMatch(r -> Set.of("FAILED", "REJECTED").contains(optional(r, "status")));
    }
    private static List<JsonNode> http(JsonNode s) { return array(s, "http"); }
    private static String resultPath(JsonNode command) {
        return "/internal/agent/tasks/" + text(command, "taskId") + "/work-items/" + text(command, "workItemId") + "/reassignments/" + text(command, "reassignmentId") + "/commands/" + text(command, "commandId") + "/result-commit";
    }
    private static void assertCommittedBusiness(JsonNode s, int index, int expected) {
        assertEquals(expected, integer(s, "artifacts")); assertEquals(expected, integer(s, "submittedEvents"));
        JsonNode work = s.get("work").get(index); assertEquals("submitted", text(work, "status")); assertTrue(bool(work, "hasResult")); assertTrue(bool(work, "leaseCleared"));
        assertEquals(integer(s, "events"), integer(s, "eventVersion"));
    }
    private static void assertCommitted(JsonNode s, int index, int expected) {
        assertCommittedBusiness(s, index, expected); JsonNode delivery = s.get("deliveries").get(index);
        assertEquals("SUCCEEDED", text(delivery, "status")); assertEquals(10, integer(delivery, "version"));
    }
    private static void assertNoSucceeded(JsonNode s) { for (JsonNode d : array(s, "deliveries")) assertNotEquals("SUCCEEDED", text(d, "status")); }
    private static void assertD06Sequence(JsonNode s, int index) {
        List<JsonNode> acks = http(s).stream().filter(r -> "ACK".equals(optional(r, "category")) && integer(r, "status") == 200).toList();
        assertEquals(List.of("RECEIVED", "STARTED", "SUCCEEDED"), acks.stream().map(r -> text(r, "businessStatus")).toList());
        assertEquals(List.of(8L, 9L, 10L), acks.stream().map(r -> integer(r, "version")).toList());
        assertTrue(bool(acks.get(0), "firstAck")); assertEquals(8, integer(acks.get(1), "lastConfirmedVersion"));
        assertEquals(9, integer(acks.get(2), "lastConfirmedVersion"));
        List<JsonNode> leases = http(s).stream().filter(r -> Set.of("LEASE", "START", "HEARTBEAT").contains(optional(r, "category"))
            && "POST".equals(optional(r, "method")) && integer(r, "index") == index && integer(r, "status") == 200).toList();
        assertTrue(leases.size() >= 3); assertEquals("LEASE", text(leases.get(0), "category")); assertEquals("START", text(leases.get(1), "category"));
        assertEquals(5, integer(leases.get(0), "expectedVersion"));
        long confirmedVersion = integer(leases.get(0), "workVersion");
        for (int n = 1; n < leases.size(); n++) {
            JsonNode receipt = leases.get(n); assertTrue(bool(receipt, "actorMatches"));
            assertEquals(confirmedVersion, integer(receipt, "expectedVersion")); confirmedVersion = integer(receipt, "workVersion");
        }
        assertTrue(bool(leases.get(0), "actorMatches"));
        JsonNode resultPost = http(s).stream().filter(r -> "RESULT".equals(optional(r, "category"))
            && "POST".equals(optional(r, "method")) && integer(r, "index") == index && integer(r, "status") == 200).findFirst().orElseThrow();
        assertEquals(confirmedVersion, integer(resultPost, "expectedVersion"));
        List<JsonNode> heartbeats = http(s).stream().filter(r -> "HEARTBEAT".equals(optional(r, "category")) && integer(r, "index") == index && integer(r, "status") == 200).toList();
        assertFalse(heartbeats.isEmpty()); long last = integer(heartbeats.getLast(), "workVersion");
        assertEquals(last + 1, integer(s.get("work").get(index), "version"));
    }
    private static void assertRecoveryOnly(JsonNode s, boolean priorAck) {
        for (JsonNode r : http(s)) {
            assertFalse(Set.of("START", "HEARTBEAT").contains(optional(r, "category")));
            assertFalse("LEASE".equals(optional(r, "category")) && "POST".equals(optional(r, "method")));
            assertFalse("RESULT".equals(optional(r, "category")) && "POST".equals(optional(r, "method")));
            if ("RESULT".equals(optional(r, "category")) && "GET".equals(optional(r, "method"))) assertTrue(bool(r, "readOnly"));
        }
        if (priorAck) assertTrue(http(s).stream().anyMatch(r -> "ACK".equals(optional(r, "category")) && "PRIOR".equals(optional(r, "kind")) && integer(r, "version") == 10));
    }
    private static void assertUniqueCheckpoint(JsonNode s, int index, String command) {
        JsonNode a = agent(s, index); assertTrue(bool(a, "credentialsAbsent"));
        assertEquals(1, array(a, "ledger").stream().filter(e -> command.equals(text(e, "commandId"))).count());
        assertEquals(1, array(a, "inbox").stream().filter(e -> command.equals(text(e, "commandId"))).count());
        assertEquals(0, array(a, "pending").size());
    }

    private record Source(Path api, String apiCommit, String apiTree, Path client, Path artifact, Path node, String path) { }
    private Source source() throws Exception {
        Path api = canonical(Path.of(System.getProperty("ur04.api.root"))); String apiCommit = System.getenv("UR04_API_COMMIT");
        Path client = canonical(Path.of(required("UR04_CLIENT_ROOT"))), artifact = canonical(Path.of(required("UR04_ARTIFACT_ROOT"))), node = canonical(Path.of(required("UR04_NODE_BIN")));
        if (!CLIENT.equals(required("UR04_CLIENT_COMMIT")) || apiCommit == null || !apiCommit.matches("[0-9a-f]{40}")) throw new SafeFailure("FIXED_SOURCE_INPUTS_REQUIRED");
        String path = node.getParent() + ":/usr/bin:/bin";
        if (!apiCommit.equals(git(api, path, "rev-parse", "HEAD")) || !git(api, path, "status", "--porcelain=v1", "--untracked-files=all").isEmpty()) throw new SafeFailure("API_EXACT_CLEAN_CHECKOUT_REQUIRED");
        git(api, path, "merge-base", "--is-ancestor", BASELINE, apiCommit);
        String tree = git(api, path, "rev-parse", "HEAD^{tree}"); if (!tree.matches("[0-9a-f]{40}")) throw new SafeFailure("API_TREE_REQUIRED");
        if (!Files.isExecutable(node)) throw new SafeFailure("PINNED_NODE_EXECUTABLE_REQUIRED");
        System.out.println("UR04_SOURCE apiCommit=" + apiCommit + " apiTree=" + tree + " clientCommit=" + CLIENT + " model=SYNTHETIC_NOT_PROVIDER");
        return new Source(api, apiCommit, tree, client, artifact, node, path);
    }
    private static Path canonical(Path p) throws Exception { if (!p.isAbsolute() || !p.normalize().equals(p) || !p.toRealPath().equals(p)) throw new SafeFailure("CANONICAL_INPUT_REQUIRED"); return p; }
    private static String required(String key) { String v = System.getenv(key); if (v == null || v.isBlank()) throw new SafeFailure("FIVE_PAIRED_INPUTS_REQUIRED"); return v; }
    private static String git(Path root, String path, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("/usr/bin/git", "-C", root.toString())); command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command); builder.environment().clear(); builder.environment().putAll(Map.of("PATH", path, "LANG", "C.UTF-8"));
        Process p = builder.start(); byte[] stdout = p.getInputStream().readAllBytes(); p.getErrorStream().readAllBytes();
        if (p.waitFor() != 0) throw new SafeFailure("API_GIT_PROOF_FAILED"); return new String(stdout, StandardCharsets.UTF_8).trim();
    }

    private final class Lane implements AutoCloseable {
        final Path root; final Source source; final Path nodeScript, childScript; final List<String> authorizations = new ArrayList<>();
        JsonNode lastServer, lastNode; Map<String, String> provenance = Map.of(); boolean evidenceEmitted;
        Child java, node; List<JsonNode> commands = new ArrayList<>(); final List<OwnedChild> owned = new ArrayList<>(); final Set<Long> nodeParents = new HashSet<>();
        Lane(String fault) throws Exception {
            source = source(); root = Files.createTempDirectory(temporaryDirectory, "ur04-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
            for (String name : List.of("home", "tmp")) Files.createDirectory(root.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            nodeScript = resource("e05-runtime-http-client.mjs"); childScript = resource("nonpaid-execution-child.mjs");
            try {
                stage = "SOURCE_AND_ARTIFACT_PROOF";
                try (Child preparation = nodeChild()) {
                    Map<String, Object> init = baseInput(); init.put("op", "PREPARE"); init.put("childModule", childScript.toString()); init.put("nodeBin", source.node().toString());
                    JsonNode prepared = preparation.exchange(init, "NODE_PREPARED"); preparation.exitZero();
                    for (int i = 0; i < 3; i++) { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); authorizations.add("rta1_" + HexFormat.of().formatHex(bytes)); }
                    java = javaChild(); Map<String, Object> input = new LinkedHashMap<>(); input.put("op", "INIT"); input.put("root", root.toString()); input.put("fault", fault);
                    input.put("initialize", true); input.put("authorizations", authorizations); input.put("manifests", prepared.get("manifests"));
                    stage = "REAL_JAVA_TOMCAT_WS_BOOT"; JsonNode ready = java.exchange(input, "JAVA_READY"); startNode(ready, true); awaitRegistration();
                }
            } catch (Throwable error) { close(); throw error; }
        }
        Map<String, Object> baseInput() {
            Map<String, Object> m = new LinkedHashMap<>(); m.put("root", root.toString()); m.put("sourceRoot", source.client().toString()); m.put("artifactRoot", source.artifact().toString()); m.put("clientCommit", CLIENT); return m;
        }
        Path resource(String name) throws Exception {
            Path p = root.resolve(name); try (InputStream in = getClass().getResourceAsStream("/ur04/" + name)) { if (in == null) throw new SafeFailure("RESOURCE_REQUIRED"); Files.copy(in, p); }
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); return p;
        }
        Child nodeChild() throws Exception {
            ProcessBuilder p = isolated(List.of(source.node().toString(), nodeScript.toString())); p.environment().put("UR04_PRIVATE_CHILD", "1"); return new Child(p.start());
        }
        Child javaChild() throws Exception {
            String cp = System.getProperty("ur04.fixture.classpath"); if (cp == null || cp.isBlank()) throw new SafeFailure("FIXTURE_CLASSPATH_REQUIRED");
            Path bin = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
            Child c = new Child(isolated(List.of(bin.toString(), "-Djava.io.tmpdir=" + root.resolve("tmp"), "-Duser.home=" + root.resolve("home"), "-Duser.timezone=UTC", "-cp", cp, Ur04E05HttpFixture.class.getName())).start());
            try { assertEquals(c.process.pid(), integer(c.receipt("JAVA_BOOT"), "pid")); System.out.println("UR04_JAVA_MAIN_ENTERED 1 pid=" + c.process.pid()); return c; }
            catch (Throwable e) { c.close(); throw e; }
        }
        ProcessBuilder isolated(List<String> args) {
            ProcessBuilder b = new ProcessBuilder(args); b.directory(root.toFile()); b.environment().clear();
            b.environment().putAll(Map.of("PATH", source.path(), "HOME", root.resolve("home").toString(), "TMPDIR", root.resolve("tmp").toString(), "LANG", "C.UTF-8", "TZ", "UTC")); return b;
        }
        void startNode(JsonNode ready, boolean fresh) throws Exception {
            assertEquals(java.process.pid(), integer(ready, "pid")); long port = integer(ready, "port"); if (port <= 0 || port > 65535) throw new SafeFailure("LOOPBACK_PORT_REQUIRED");
            commands = array(ready, "commands"); node = nodeChild(); nodeParents.add(node.process.pid()); Map<String, Object> input = baseInput(); input.put("op", "INIT"); input.put("apiOrigin", "http://127.0.0.1:" + port);
            if (fresh) input.put("authorizations", authorizations); JsonNode boot = node.exchange(input, "NODE_BOOT"); assertEquals(node.process.pid(), integer(boot, "pid"));
            assertEquals(CLIENT, text(boot, "sourceCommit")); assertEquals("0827ce904179862ab55648fe99b780cea94faf98", text(boot, "sourceTree"));
            provenance = Map.of("archiveSha256", text(boot, "archiveSha256"), "sourceManifestSha256", text(boot, "sourceManifestSha256"),
                "artifactManifestSha256", text(boot, "artifactManifestSha256"), "nodeSha256", text(boot, "nodeSha256"), "cleanRun", text(boot, "cleanRun"));
            for (String key : List.of("archiveSha256", "sourceManifestSha256", "artifactManifestSha256", "nodeSha256"))
                if (!provenance.get(key).matches("[0-9a-f]{64}")) throw new SafeFailure("PROVENANCE_HASH_REQUIRED");
            if (!provenance.get("cleanRun").matches("[A-Za-z0-9._:-]+")) throw new SafeFailure("PROVENANCE_RUN_REQUIRED");
            System.out.println("UR04_NODE_BOOT pid=" + node.process.pid() + " version=" + text(boot, "nodeVersion")
                + " nodeSha256=" + provenance.get("nodeSha256") + " sourceManifestSha256=" + provenance.get("sourceManifestSha256")
                + " archiveSha256=" + provenance.get("archiveSha256") + " artifactManifestSha256=" + provenance.get("artifactManifestSha256")
                + " cleanRun=" + provenance.get("cleanRun"));
        }
        void awaitRegistration() throws Exception {
            stage = "REAL_NATIVE_THREE_REGISTRATIONS";
            boolean first = commands.isEmpty();
            waitNode(s -> {
                if (first && array(s, "agents").stream().anyMatch(a -> bool(a, "isolated")))
                    throw new SafeFailure("INITIAL_SUBJECT_ISOLATED");
                return array(s, "agents").stream().filter(a -> bool(a, "ready")).count() >= (first ? 3 : 2);
            });
            System.out.println("UR04_PHASE NATIVE_REGISTERED initial=" + first);
        }
        JsonNode seed(int index) throws Exception {
            stage = "HISTORICAL_E05_SEED"; JsonNode receipt = java.exchange(Map.of("op", "SEED_WORK", "agentIndex", index), "WORK_SEEDED"); commands = array(receipt, "commands");
            return commands.stream().filter(c -> integer(c, "agentIndex") == index).findFirst().orElseThrow(() -> new SafeFailure("ORIGINAL_CODEC_COMMAND_REQUIRED"));
        }
        void dispatch(int index) throws Exception { stage = "REAL_WS_CODEC_DISPATCH"; assertTrue(bool(java.exchange(Map.of("op", "DISPATCH", "agentIndex", index), "DISPATCHED"), "sent")); }
        JsonNode nodeSnapshot() throws Exception { lastNode = node.exchange(Map.of("op", "SNAPSHOT"), "NODE_SNAPSHOT"); return lastNode; }
        JsonNode serverSnapshot() throws Exception { lastServer = java.exchange(Map.of("op", "SNAPSHOT"), "SNAPSHOT"); return lastServer; }
        JsonNode waitNode(Predicate<JsonNode> condition) throws Exception {
            for (;;) { JsonNode s = nodeSnapshot(); if (bool(s, "runtimeFailed")) throw new SafeFailure("ORIGINAL_RUNTIME_FAILED"); if (condition.test(s)) return s; Thread.sleep(25); }
        }
        void releaseAfterRealHeartbeat(int index) throws Exception {
            awaitRealHeartbeat(index);
            node.exchange(Map.of("op", "RELEASE", "agentIndex", index), "CHILD_RELEASED");
        }
        void awaitRealHeartbeat(int index) throws Exception {
            stage = "ORIGINAL_LEASE_START_CHILD_HEARTBEAT";
            JsonNode entered = waitNode(s -> {
                if (hasInbox(s, index, "recovery_required", false) || hasTerminalFailure(s, index))
                    throw new SafeFailure("ORIGINAL_EXECUTION_FAILED_BEFORE_CHILD");
                return children(s, index) == 1;
            }); trackChildren(entered);
            for (;;) {
                JsonNode s = serverSnapshot(); if (http(s).stream().anyMatch(r -> "HEARTBEAT".equals(optional(r, "category")) && integer(r, "index") == index && integer(r, "status") == 200)) break;
                // No performance gate/deadline: require actual business heartbeat, or actual terminal failure.
                JsonNode n = nodeSnapshot(); if (hasInbox(n, index, "recovery_required", false)) throw new SafeFailure("REAL_HEARTBEAT_FAILED"); Thread.sleep(25);
            }
        }
        void terminal(int index) throws Exception {
            stage = "ORIGINAL_RESULT_AND_HTTP_D06";
            waitNode(s -> {
                if (hasTerminalFailure(s, index)) throw new SafeFailure("ORIGINAL_EXECUTION_TERMINAL_FAILURE");
                return array(agent(s, index), "confirmed").stream().anyMatch(c -> c.get("commit") != null && c.get("commit").isObject()
                    && "SUCCEEDED".equals(optional(c.get("commit"), "status")) && integer(c.get("commit"), "deliveryVersion") == 10)
                    && array(agent(s, index), "pending").isEmpty();
            });
            System.out.println("UR04_PHASE TERMINAL_CONFIRMED agentIndex=" + index);
        }
        void mutate(int index, String mutation) throws Exception { java.exchange(Map.of("op", "MUTATE", "agentIndex", index, "mutation", mutation), "MUTATED"); }
        void restart() throws Exception {
            stage = "INDEPENDENT_JAVA_NODE_RESTART_NO_RESEED"; if (java.process.isAlive() || node.process.isAlive()) throw new SafeFailure("BOTH_ORIGINAL_PROCESSES_MUST_EXIT");
            java = javaChild(); JsonNode ready = java.exchange(Map.of("op", "INIT", "root", root.toString(), "initialize", false, "fault", "NONE"), "JAVA_READY"); startNode(ready, false); awaitRegistration();
        }
        void stopBoth() throws Exception { stage = "OWNED_PROCESS_STOP"; node.stop("NODE_STOPPED"); java.stop("STOPPED"); }
        JsonNode persisted() throws Exception {
            stage = "INDEPENDENT_JDBC_FILE_REOPEN"; if (java.process.isAlive()) throw new SafeFailure("PARENT_JDBC_ONLY_AFTER_CHILD_EXIT");
            var ds = Ur04E05HttpFixture.dataSource(root, true); JdbcTemplate jdbc = new JdbcTemplate(ds);
            try { return Ur04E05HttpFixture.JSON.valueToTree(Ur04E05HttpFixture.snapshot(jdbc)); }
            finally { jdbc.execute("SHUTDOWN"); }
        }
        void trackChildren(JsonNode snapshot) {
            for (JsonNode a : array(snapshot, "agents")) for (JsonNode c : array(a, "children")) {
                long pid = integer(c, "pid"), parentPid = integer(c, "parentPid");
                if (!nodeParents.contains(parentPid)) throw new SafeFailure("CHILD_OBSERVED_PARENT_REQUIRED");
                assertTrue(bool(c, "synthetic")); assertTrue(text(c, "cwdSha256").matches("[0-9a-f]{64}"));
                Set<String> allowed = Set.of("PATH", "LANG", "LC_ALL", "TZ", "TMPDIR", "HOME", "CODEX_HOME", "CYF_WORKSPACE_FILE_TOOLCHAIN_PYTHON", "CYF_WORKSPACE_FILE_DELIVERY_TOOL");
                for (JsonNode key : array(c, "environmentKeys")) { if (!key.isTextual() || !allowed.contains(key.asText())) throw new SafeFailure("CHILD_ENV_EXACT_ALLOWLIST_REQUIRED"); }
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(h -> {
                    // Historical receipts survive restart; never use an old PID receipt to adopt a new process.
                    var start = h.info().startInstant().orElseThrow(() -> new SafeFailure("CHILD_START_IDENTITY_REQUIRED"));
                    boolean known = owned.stream().anyMatch(o -> o.pid == pid && o.start.equals(start));
                    if (parentPid != node.process.pid()) {
                        if (known) throw new SafeFailure("PREVIOUS_OWNED_CHILD_STILL_ALIVE");
                        return; // recycled historical PID: not our process, do not control it
                    }
                    if (h.parent().map(ProcessHandle::pid).orElse(-1L) != parentPid) throw new SafeFailure("LIVE_CHILD_PARENT_REQUIRED");
                    if (!known) owned.add(new OwnedChild(pid, start));
                });
            }
        }
        void emitObservedEvidence() {
            if (evidenceEmitted) return;
            evidenceEmitted = true;
            // These are LAST_OBSERVED receipts, not a PASS label. JUnit terminal status is authoritative.
            Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("stage", "LAST_OBSERVED");
            receipt.put("apiCommit", source.apiCommit()); receipt.put("apiTree", source.apiTree()); receipt.put("clientCommit", CLIENT);
            receipt.put("provenance", provenance); receipt.put("syntheticNotProvider", true);
            if (java != null) receipt.put("javaPid", java.process.pid()); if (node != null) receipt.put("nodePid", node.process.pid());
            if (lastServer != null) {
                for (String key : List.of("ackUpdates", "workUpdates", "artifactInserts", "submittedAttempts", "artifacts", "submittedEvents", "events", "eventVersion")) receipt.put(key, integer(lastServer, key));
                receipt.put("businessSha256", text(lastServer, "businessSha256")); receipt.put("http", http(lastServer));
                receipt.put("work", array(lastServer, "work")); receipt.put("deliveries", array(lastServer, "deliveries"));
                receipt.put("generations", array(lastServer, "runtimes").stream().map(r -> integer(r, "generation")).toList());
            }
            if (lastNode != null) {
                List<Map<String, Object>> subjects = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    JsonNode a = agent(lastNode, i);
                    subjects.add(Map.of("agentIndex", i, "children", children(lastNode, i), "ready", bool(a, "ready"), "isolated", bool(a, "isolated"),
                        "credentialsAbsent", bool(a, "credentialsAbsent"), "inboxCount", array(a, "inbox").size(), "ledgerCount", array(a, "ledger").size(),
                        "pendingCount", array(a, "pending").size(), "conflictsCount", array(a, "conflicts").size()));
                }
                receipt.put("subjects", subjects);
            }
            System.out.println("UR04_RECEIPT " + Ur04E05HttpFixture.JSON.writeValueAsString(receipt));
        }
        @Override public void close() {
            // Reporting must never prevent exact owned process cleanup or mask the original failure.
            try { emitObservedEvidence(); } catch (Throwable ignored) { System.out.println("UR04_RECEIPT_EMISSION_FAILED"); }
            boolean cleanupFailed = false;
            try { if (node != null) node.close(); } catch (Throwable failure) { cleanupFailed = true; }
            for (OwnedChild o : owned) { try { o.close(); } catch (Throwable failure) { cleanupFailed = true; } }
            try { if (java != null) java.close(); } catch (Throwable failure) { cleanupFailed = true; }
            // Attempt every exact handle even if another close fails; never control an unrelated process.
            if (cleanupFailed) throw new SafeFailure("OWNED_LANE_CLEANUP_FAILED");
            // @TempDir deletes only this fixture's exclusive root after exact handles exit.
        }
    }
    private record OwnedChild(long pid, java.time.Instant start) implements AutoCloseable {
        @Override public void close() {
            ProcessHandle.of(pid).filter(h -> h.isAlive() && h.info().startInstant().filter(start::equals).isPresent()).ifPresent(h -> {
                h.destroy(); while (h.isAlive()) { h.destroyForcibly(); try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SafeFailure("OWNED_CHILD_STOP_INTERRUPTED"); } }
            });
        }
    }
    private static final class Child implements AutoCloseable {
        final Process process; final BufferedWriter input; final BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>(); final List<JsonNode> pending = new ArrayList<>();
        final ExecutorService drain = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "ur04-private-pipe"); t.setDaemon(true); return t; });
        volatile boolean ended; volatile String launcher = "NO_SAFE_LAUNCHER_DIAGNOSTIC";
        Child(Process p) {
            process = p; input = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            drain.submit(() -> {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = out.readLine()) != null) if (line.startsWith(Ur04E05HttpFixture.PREFIX)) queue.add(Ur04E05HttpFixture.JSON.readTree(line.substring(Ur04E05HttpFixture.PREFIX.length())));
                } catch (Exception ignored) { /* no raw input/exception may be exported */ } finally { ended = true; }
            });
            drain.submit(() -> {
                try (BufferedReader error = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = error.readLine()) != null) { String safe = Ur04E05HttpFixture.safeLauncherLine(line); if (safe != null) launcher = safe; }
                } catch (IOException ignored) { /* private discarded stderr */ }
            });
        }
        JsonNode exchange(Object request, String expected) throws Exception {
            try { input.write(Ur04E05HttpFixture.JSON.writeValueAsString(request)); input.newLine(); input.flush(); }
            catch (IOException e) { throw new SafeFailure("PRIVATE_PIPE_WRITE_FAILED"); }
            return receipt(expected);
        }
        JsonNode receipt(String expected) throws Exception {
            for (;;) {
                for (var it = pending.iterator(); it.hasNext();) { JsonNode n = it.next(); if ("ERROR".equals(optional(n, "stage"))) throw errorFailure(n);
                    if (expected.equals(optional(n, "stage"))) { it.remove(); return n; } }
                JsonNode next = queue.poll(100, TimeUnit.MILLISECONDS);
                if (next != null) {
                    if ("ERROR".equals(optional(next, "stage"))) throw errorFailure(next);
                    // HTTP observations are also durable in child memory until snapshot; don't grow an unbounded unused pipe backlog.
                    if (!"HTTP_OBSERVED".equals(optional(next, "stage"))) pending.add(next);
                } else if (ended) throw new SafeFailure("CHILD_EXIT_BEFORE_" + expected + " " + launcher);
            }
        }
        private static SafeFailure errorFailure(JsonNode n) {
            Set<String> codes = Set.of("UR04_JAVA_FAILURE", "UR04_NODE_FAILURE", "NATIVE_HANDLER_ASSEMBLY_FAILED", "ORIGINAL_RUNTIME_FAILURE", "PREPARE_OUTER_TRANSACTION_FORBIDDEN");
            String code = optional(n, "code"), at = optional(n, "at"), type = optional(n, "type"), rootType = optional(n, "rootType"), sql = optional(n, "sqlState");
            String result = codes.contains(code) || Ur04E05HttpFixture.FIXTURE_CODES.contains(code) ? code : "CHILD_FAILURE";
            if (Set.of("BOOT_INPUT", "BOOT_SCHEMA", "BOOT_HTTP", "BOOT_MYBATIS_SPRING", "BOOT_TOMCAT_START", "PRIVATE_INPUT", "SOURCE_PROOF", "ARTIFACT_PROOF", "ORIGINAL_PAYLOAD_VALIDATE", "PRIVATE_SUBJECT_PREPARE", "ORIGINAL_HOST_CONFIG", "ORIGINAL_HOST_RUNNING").contains(at)) result += " at=" + at;
            if (Ur04E05HttpFixture.allowedDiagnosticType(type)) result += " type=" + type;
            if (Ur04E05HttpFixture.allowedDiagnosticType(rootType)) result += " rootType=" + rootType;
            String bean = optional(n, "bean"), missing = optional(n, "missingClass");
            if (Ur04E05HttpFixture.DIAGNOSTIC_BEANS.contains(bean)) result += " bean=" + bean;
            if (Ur04E05HttpFixture.DIAGNOSTIC_CLASSES.contains(missing)) result += " missingClass=" + missing;
            String inputFailure = optional(n, "failureCode");
            if (Set.of("ARTIFACT_ALIAS_FORBIDDEN", "ARTIFACT_FULL_INVENTORY_REQUIRED", "ARTIFACT_LINK_PROOF_REQUIRED", "ARTIFACT_MEMBER_MISMATCH", "CHILD_INTERPRETER_PATH_UNSAFE", "CLEAN_ARTIFACT_EVIDENCE_REQUIRED", "CLIENT_COMMIT_REQUIRED", "PRIVATE_CHILD_ONLY", "CURRENT_TRANSPORT_REQUIRED", "ENGINE_SOURCE_BINDING_REQUIRED", "EXACT_AGENT_INDEX_REQUIRED", "EXPLICIT_STOP_REQUIRED", "MEMBER_UNSAFE", "NEGATIVE_ALLOWLIST_REQUIRED", "NEGATIVE_COMMAND_BINDING_REQUIRED", "NODE_20_20_2_REQUIRED", "NODE_ARTIFACT_BINARY_BINDING_REQUIRED", "ORIGINAL_CONFLICT_AUDIT_REQUIRED", "ONE_ORIGINAL_CHECKPOINT_REQUIRED", "ONE_ORIGINAL_MATERIAL_REQUIRED", "ORIGINAL_MATERIAL_REQUIRED", "OWNED_SUBJECT_SOCKET_REQUIRED", "PATH_ALIAS_FORBIDDEN", "PATH_CANONICAL_REQUIRED", "PATH_TYPE_REQUIRED", "PIPE_REQUEST_REQUIRED", "PRIOR_TERMINAL_REQUIRED", "PRIVATE_INIT_REQUIRED", "PRIVATE_THREE_SUBJECTS_REQUIRED", "REAL_LEASE_FOR_NEGATIVE_REQUIRED", "RUNTIME_SOURCE_BINDING_REQUIRED", "SOURCE_ALIAS_FORBIDDEN", "SOURCE_ARCHIVE_MISMATCH", "SOURCE_FULL_TREE_MISMATCH", "SOURCE_IDENTITY_REQUIRED", "SOURCE_MEMBER_HASH_REQUIRED", "SOURCE_MEMBER_MISMATCH", "SOURCE_UNEXPECTED_MEMBER", "SYNTHETIC_AUTH_REQUIRED", "TRACKED_SYNTHETIC_MODULE_REQUIRED", "UNKNOWN_PRIVATE_OPERATION").contains(inputFailure)) result += " failureCode=" + inputFailure;
            if (sql.matches("[A-Z0-9]{5}")) result += " sqlState=" + sql;
            JsonNode errno = n.get("errno"); if (errno != null && errno.isIntegralNumber() && errno.canConvertToLong() && errno.longValue() > 0 && errno.longValue() <= 4095) result += " errno=" + errno.longValue();
            return new SafeFailure(result);
        }
        void stop(String receipt) throws Exception { if (process.isAlive()) { exchange(Map.of("op", "STOP"), receipt); exitZero(); } }
        void exitZero() throws Exception { if (process.waitFor() != 0) throw new SafeFailure("CHILD_EXIT_NOT_ZERO"); }
        void crash() throws Exception {
            // Capture exact descendants while this owned parent is alive; no PID search/global service control.
            List<OwnedChild> descendants = process.descendants().filter(ProcessHandle::isAlive)
                    .map(h -> new OwnedChild(h.pid(), h.info().startInstant()
                            .orElseThrow(() -> new SafeFailure("OWNED_DESCENDANT_START_REQUIRED")))).toList();
            process.destroyForcibly(); process.waitFor();
            for (OwnedChild h : descendants) h.close();
        }
        @Override public void close() {
            try { if (process.isAlive()) crash(); } catch (Exception e) { throw new SafeFailure("OWNED_PROCESS_CLEANUP_FAILED"); }
            finally { drain.shutdownNow(); }
        }
    }
    private static final class SafeFailure extends RuntimeException {
        final String code; SafeFailure(String code) { super(code, null, false, false); this.code = code; }
    }
}
