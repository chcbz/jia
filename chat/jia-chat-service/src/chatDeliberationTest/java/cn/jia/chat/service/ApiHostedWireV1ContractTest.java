package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ApiHostedWireV1ContractTest {
    private static final String BASELINE_COMMIT = "0e879cc9dd8ff2927a9a5e56ea8cadc781105cb1";
    private static final String FIXTURE = "api-hosted-wire-v1.json";
    private static final String GENERATOR = "cn.jia.chat.service.ApiHostedWireV1ContractTest";
    private static final String CANONICAL_RULE = "CanonicalContextJson v1; UTF-8; lexical object keys; "
            + "preserved array order; integral numbers only; one LF terminator included in sha256";

    @Test
    void hostedWireMatchesCheckedInCanonicalFixtureByteForByte() throws Exception {
        byte[] generated = generatedWire();
        assertArrayEquals(resource("/contracts/" + FIXTURE), generated,
                "Hosted wire contract drift requires an intentional cross-repository contract update");
    }

    @Test
    @SuppressWarnings("unchecked")
    void provenancePinsGeneratorBaselineCanonicalRuleAndFixtureDigest() throws Exception {
        Map<String, Object> manifest = JsonUtil.getMapper().readValue(
                resource("/contracts/api-hosted-wire-v1.provenance.json"), Map.class);
        byte[] fixture = resource("/contracts/" + FIXTURE);

        assertEquals(BASELINE_COMMIT, manifest.get("apiCommitBaseline"));
        assertEquals(GENERATOR, manifest.get("generatorClass"));
        assertEquals(FIXTURE, manifest.get("fixture"));
        assertEquals(CANONICAL_RULE, manifest.get("canonicalRule"));
        assertEquals("ChatDeliberationService.eventPayload -> ChatDeliberationOutboxRelay.hostedWire",
                manifest.get("productionPath"));
        assertEquals(sha256(fixture), manifest.get("fixtureSha256"));
    }

    private byte[] generatedWire() throws Exception {
        ChatDeliberationService service = new ChatDeliberationService(
                mock(ChatDeliberationDao.class), mock(ChatConversationDao.class),
                mock(ChatMessageDao.class), mock(AgentService.class));
        ChatDeliberationOutboxRelay relay = new ChatDeliberationOutboxRelay(
                mock(ChatDeliberationOutboxService.class), service, mock(AgentWebSocketHandler.class),
                mock(BuiltinHallAgentSupport.class), new ChatConversationEventBroker(),
                mock(ChatConversationService.class), mock(ChatClient.class));

        String tenantId = "tenant-contract";
        String ownerJiacn = "owner-contract";
        String clientId = "client-contract";
        String conversationId = "42";
        long generation = 3L;
        String requestId = "request-contract-v1";
        long requestRevision = 7L;
        String targetAgentId = "hosted-agent-contract";
        long messageId = 9_007_199_254_740_993L;
        Clock clock = Clock.fixed(Instant.parse("2026-09-25T00:00:00.123Z"), ZoneOffset.UTC);
        long occurredAt = clock.millis();

        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(42L).setJiacn(ownerJiacn).setConversationType("juyiting")
                .setConversationScopeType("bounty").setConversationScopeKey("task-contract-17")
                .setTaskId("task-contract-17")
                .setTargetAgentIds("[\"hosted-agent-contract\",\"builtin-songjiang\"]")
                .setLifecycleGeneration(generation);
        conversation.setTenantId(tenantId);
        conversation.setClientId(clientId);

        JuyitingConversationScope scope = new JuyitingConversationScope(
                "bounty", "task-contract-17", "task-contract-17", targetAgentId,
                List.of(targetAgentId), List.of(targetAgentId, "builtin-songjiang"));
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId("task-contract-17");
        task.setTenantId(tenantId);
        task.setClientId(clientId);
        task.setTitle("Contract task");
        task.setStatus("RUNNING");
        task.setAssignedAgentId(targetAgentId);
        task.setAssignedAgentIds(List.of(targetAgentId, "builtin-songjiang"));
        task.setTaskVersion("17");
        List<Map<String, Object>> inputRefs = List.of(
                Map.of("type", "conversation", "id", conversationId),
                Map.of("type", "task", "id", "task-contract-17"),
                Map.of("type", "message", "id", Long.toString(messageId - 1)));

        Map<String, Object> sourceVector = invoke(service, "sourceVector",
                new Class<?>[]{long.class, long.class, AgentTaskDTO.class}, generation, messageId, task);
        Map<String, Object> facts = invoke(service, "factsManifest",
                new Class<?>[]{ChatConversationEntity.class, JuyitingConversationScope.class,
                        String.class, AgentTaskDTO.class, List.class, long.class},
                conversation, scope, targetAgentId, task, inputRefs, messageId);
        String contextHash = ChatDeliberationService.contextDigest(sourceVector, facts);
        String turnId = stableId(service, "turn", tenantId, ownerJiacn, clientId, requestId, targetAgentId);
        String dispatchId = stableId(service, "dispatch", tenantId, ownerJiacn, clientId, requestId,
                targetAgentId);
        String snapshotId = stableId(service, "ctx", turnId, contextHash);
        String eventId = stableId(service, "evt", dispatchId, "DISPATCH");

        ChatMessageDTO input = new ChatMessageDTO();
        input.setSenderType("user");
        input.setSenderName("契约用户");
        input.setMetadata(Map.of(
                "schemaVersion", "1",
                "correlationId", "correlation-contract-v1",
                "participantAgentIds", List.of(targetAgentId, "builtin-songjiang"),
                "untrustedIgnored", "must-not-appear"));
        Map<String, Object> eventPayload = service.eventPayload(
                tenantId, ownerJiacn, clientId, conversationId, generation, requestId,
                requestRevision, turnId, dispatchId, targetAgentId, snapshotId, contextHash,
                InteractionRoute.INSPECT, "Inspect the fixed contract context", input, scope,
                occurredAt, sourceVector, facts);

        ChatDispatchOutboxEntity row = new ChatDispatchOutboxEntity()
                .setEventId(eventId).setTenantId(tenantId).setOwnerJiacn(ownerJiacn)
                .setClientId(clientId).setTurnId(turnId).setDispatchId(dispatchId)
                .setEventType("DISPATCH").setStatus("READY")
                .setPayloadJson(CanonicalContextJson.write(eventPayload)).setVersion(0L)
                .setAvailableAt(occurredAt).setAttemptCount(0).setFencingToken(0L)
                .setCreatedAt(occurredAt).setUpdatedAt(occurredAt);
        Map<String, Object> wire = relay.hostedWire(row, eventPayload);
        assertNotNull(wire.get("payload"));
        assertEquals(contextHash, wire.get("contextHash"));
        return (CanonicalContextJson.write(wire) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String stableId(ChatDeliberationService service, String prefix, String... parts)
            throws Exception {
        Method method = ChatDeliberationService.class.getDeclaredMethod("stableId", String.class, String[].class);
        method.setAccessible(true);
        return (String) method.invoke(service, prefix, parts);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return (T) method.invoke(target, args);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream stream = ApiHostedWireV1ContractTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, "Missing contract resource " + name);
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
