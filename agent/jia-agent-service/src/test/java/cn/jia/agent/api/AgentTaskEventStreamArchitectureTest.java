package cn.jia.agent.api;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.service.AgentTaskEventAccessService;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.agent.service.impl.AgentTaskWorkspaceServiceImpl;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventStreamArchitectureTest {
    private static final String FROZEN_C04_WORKSPACE_API_SHA256 =
            "963949fe9fa44e9ff1022cf13fa12fe6cf82857e3967e7aa4b4ccfd6886cd437";
    private static final Map<String, String> FROZEN_C03_SHA256 = frozenC03();

    @Test
    void c05ProductionUsesReplayOnlyAndHasNoDaoMapperBrokerRabbitOrExtraBuffering()
            throws Exception {
        Path root = apiRoot();
        List<String> c05Sources = List.of(
                "agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskEventStreamController.java",
                "agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskEventProjection.java");
        String combined = "";
        for (String source : c05Sources) {
            String text = Files.readString(root.resolve(source), StandardCharsets.UTF_8);
            combined += text;
            for (String forbidden : List.of(
                    "AgentTaskEventDao", "AgentTaskEventMapper", "AgentTaskEventBroker",
                    "Rabbit", "Amqp", "onBackpressureBuffer", "collectList(",
                    ".cache(", ".share(", "new Thread(",
                    "Scheduler", "Executor", "BlockingQueue")) {
                assertFalse(text.contains(forbidden), source + " contains " + forbidden);
            }
        }
        assertEquals(1, occurrences(combined, "replayService.replay("));
        assertTrue(combined.contains("new ManagedSseEmitter(SSE_TIMEOUT_MILLIS)"));
        assertEquals(30_000L, AgentTaskEventStreamController.SSE_TIMEOUT_MILLIS);
    }

    @Test
    void publicWorkspaceApiRemainsByteExactAndC05UsesOnlyServiceModuleAccessContract()
            throws Exception {
        Path root = apiRoot();
        Path workspaceApi = root.resolve(
                "agent/jia-agent-api/src/main/java/cn/jia/agent/service/AgentTaskWorkspaceService.java");
        assertEquals(FROZEN_C04_WORKSPACE_API_SHA256,
                sha256(Files.readAllBytes(workspaceApi)));
        assertEquals(Set.of("snapshot"), Arrays.stream(
                        AgentTaskWorkspaceService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertEquals(0, AgentTaskWorkspaceService.class.getDeclaredClasses().length);

        Path internalAccess = root.resolve(
                "agent/jia-agent-service/src/main/java/cn/jia/agent/service/AgentTaskEventAccessService.java");
        assertTrue(Files.isRegularFile(internalAccess));
        assertFalse(Files.exists(root.resolve(
                "agent/jia-agent-api/src/main/java/cn/jia/agent/service/AgentTaskEventAccessService.java")));
        assertTrue(AgentTaskEventAccessService.class.isAssignableFrom(
                AgentTaskWorkspaceServiceImpl.class));

        String controller = Files.readString(root.resolve(
                "agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskEventStreamController.java"),
                StandardCharsets.UTF_8);
        assertTrue(controller.contains("AgentTaskEventAccessService"));
        assertFalse(controller.contains("AgentTaskWorkspaceService"));
    }

    @Test
    void c05fUsesOneImmutableGateBeforeAclSnapshotReplayAndEmitterAllocation()
            throws Exception {
        Path root = apiRoot();
        String workspace = Files.readString(root.resolve(
                "agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskWorkspaceController.java"),
                StandardCharsets.UTF_8);
        String events = Files.readString(root.resolve(
                "agent/jia-agent-service/src/main/java/cn/jia/agent/api/AgentTaskEventStreamController.java"),
                StandardCharsets.UTF_8);

        assertTrue(workspace.contains("private final AgentTaskEventsGate taskEventsGate;"));
        assertTrue(events.contains("private final AgentTaskEventsGate taskEventsGate;"));
        assertOrdered(workspace, "requireJwtScope(authentication)",
                "singleRequiredQuery(request, \"actorAgentId\")",
                "taskEventsGate.allows(", "workspaceService.snapshot(");
        assertOrdered(events, "requireJwtScope(authentication)",
                "requireRawRequest(taskId, request)", "taskEventsGate.allows(",
                "accessService.authorize(", "replayService.replay(",
                "new ManagedSseEmitter(");

        assertTrue(java.lang.reflect.Modifier.isFinal(AgentTaskEventsGate.class.getModifiers()));
        for (java.lang.reflect.Field field : AgentTaskEventsGate.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertTrue(java.lang.reflect.Modifier.isFinal(field.getModifiers()),
                        field.getName());
            }
        }
        assertEquals(0, Arrays.stream(AgentTaskEventsGate.class.getMethods())
                .filter(method -> method.getName().startsWith("set"))
                .count());
    }

    @Test
    void everyAcceptedC03SourceAndTestFileRemainsByteExact() throws Exception {
        Path root = apiRoot();
        for (Map.Entry<String, String> entry : FROZEN_C03_SHA256.entrySet()) {
            Path file = root.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(file), entry.getKey());
            assertEquals(entry.getValue(), sha256(Files.readAllBytes(file)), entry.getKey());
        }
    }

    private static void assertOrdered(String source, String... tokens) {
        int previous = -1;
        for (String token : tokens) {
            int current = source.indexOf(token);
            assertTrue(current > previous, token + " is out of order");
            previous = current;
        }
    }

    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(token, at)) >= 0; at += token.length()) {
            count++;
        }
        return count;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("agent"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate API worktree root from " + current);
    }

    private static Map<String, String> frozenC03() {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        files.put("agent/jia-agent-api/src/main/java/cn/jia/agent/service/AgentTaskEventReplayService.java",
                "b8a75ad2b46bafa9ed0085909c6508a556f0df21578b0a6e111bf77b9229d035");
        files.put("agent/jia-agent-mapper/src/main/java/cn/jia/agent/dao/AgentTaskEventDao.java",
                "cb8f34995751282c67f5b330d2c47eb1fee2c9a33b111007d6d82fab67147ca6");
        files.put("agent/jia-agent-mapper/src/main/java/cn/jia/agent/dao/impl/AgentTaskEventDaoImpl.java",
                "80f5de28032a02da0ccb860c82ff086242b891814eef009fe8ebbcfa46bcddb3");
        files.put("agent/jia-agent-mapper/src/main/java/cn/jia/agent/mapper/AgentTaskEventMapper.java",
                "bbf59401bcc308faa05bc6ed4c78393b2a614e4346cd17d9e6691635e83ccc86");
        files.put("agent/jia-agent-mapper/src/test/java/cn/jia/agent/dao/AgentTaskEventReplayDaoTest.java",
                "4e21b3cb580fb40b48cf3db10c4cff91d11d47dfab833ca7d7dfaccb5e01c746");
        files.put("agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentTaskEventReplayServiceImpl.java",
                "f6c2ab6c935a5b4b44c2b6419ec68632bfe9ef45617de478285f5c77fb41c535");
        files.put("agent/jia-agent-service/src/test/java/cn/jia/agent/service/impl/AgentTaskEventRealTransactionTest.java",
                "df50b3e054c5e77848acc08168ac8557e54e5271e52e498590b7a59b7d3364c3");
        files.put("agent/jia-agent-service/src/test/java/cn/jia/agent/service/impl/AgentTaskEventReplayServiceImplTest.java",
                "234293abc7a6d43dc3f5e4bf3af12eb6944f8f872683df50ab7723ad809bf3c0");
        return Map.copyOf(files);
    }
}
