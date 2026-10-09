package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ApiHostedWireV1ContractTest {
    private static final String BASELINE_COMMIT = "3e608552a4750ad073e8c8bf4f821053e79fc660";
    private static final String FIXTURE = "api-hosted-wire-v1.json";
    private static final String GENERATOR = "cn.jia.chat.service.ApiHostedWireV1ContractTest";
    private static final String CANONICAL_RULE = "CanonicalContextJson v1; UTF-8; lexical object keys; "
            + "preserved array order; integral numbers only; one LF terminator included in sha256";

    @Test
    void hostedWireMatchesCheckedInCanonicalFixtureByteForByte() throws Exception {
        byte[] generated = generatedWire();
        // Explicit maintenance-only diagnostic; tests never rewrite checked-in golden bytes.
        String fixtureOutput = System.getenv("CYF_HOSTED_WIRE_FIXTURE_OUTPUT");
        if (fixtureOutput != null && !fixtureOutput.isBlank()) {
            Files.write(Path.of(fixtureOutput), generated);
        }
        assertArrayEquals(resource("/contracts/" + FIXTURE), generated,
                "Hosted wire contract drift requires an intentional cross-repository contract update");
        String wire = new String(generated, StandardCharsets.UTF_8);
        assertFalse(wire.contains("伪造代理身份"));
        assertTrue(wire.contains("\"authorizedContext\""));
        assertTrue(wire.contains("\"materializedRefs\":[]"));
        assertTrue(wire.contains("\"senderName\":\"契约用户\""));
        assertTrue(wire.contains("\"jiacn\":\"owner-contract\""));
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

    @Test
    @SuppressWarnings("unchecked")
    void longHistoryProducesBoundedButTruthfulHostedWireForRealClientContract() throws Exception {
        byte[] generated = generatedWire(true);
        String output = System.getenv("CYF_LONG_HISTORY_WIRE_OUTPUT");
        if (output != null && !output.isBlank()) Files.write(Path.of(output), generated);
        Map<String, Object> wire = JsonUtil.getMapper().readValue(generated, Map.class);
        Map<String, Object> snapshot = (Map<String, Object>) wire.get("contextSnapshot");
        Map<String, Object> facts = (Map<String, Object>) snapshot.get("facts");
        Map<String, Object> history = (Map<String, Object>) facts.get("authorizedContext");
        Map<String, Object> materials = (Map<String, Object>) facts.get("taskMaterials");
        assertTrue(CanonicalContextJson.write(history).getBytes(StandardCharsets.UTF_8).length <= 8192);
        assertTrue(CanonicalContextJson.write(materials).getBytes(StandardCharsets.UTF_8).length <= 8192);
        assertEquals("BOUNDED_EXTRACTIVE_NOT_COMPLETE", history.get("coverage"));
        assertEquals(200, history.get("sourceMessageCount"));
        assertTrue(((Number) history.get("availableRefsOmittedCount")).intValue() > 0);
        assertTrue(((Number) materials.get("omittedCount")).intValue() > 0);
        assertEquals(false, materials.get("complete"));
        assertTrue(String.valueOf(history.get("historyDigest")).startsWith("sha256:"));
    }

    private byte[] generatedWire() throws Exception { return generatedWire(false); }

    private byte[] generatedWire(boolean longHistory) throws Exception {
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
        List<ChatMessageEntity> history = new ArrayList<>();
        String currentText;
        Map<String, Object> taskMaterials = null;
        if (longHistory) {
            for (int index = 0; index < 200; index++) {
                history.add(scopedMessage(tenantId, ownerJiacn, clientId, conversationId,
                        messageId - 201 + index, "USER", "历史资料".repeat(1200) + index, null));
            }
            List<Map<String, Object>> references = new ArrayList<>();
            for (int index = 0; index < 180; index++) {
                references.add(Map.of("fileId", "reference-" + index + "-" + "图".repeat(12),
                        "version", 1, "role", "REFERENCE"));
            }
            taskMaterials = Map.of("status", "AVAILABLE", "complete", true, "items", references);
            currentText = "请参照这只鸟".repeat(1_200);
        } else {
            ChatMessageEntity previousUser = scopedMessage(tenantId, ownerJiacn, clientId,
                    conversationId, messageId - 1, "USER", "Previous authorized user context", null);
            ChatMessageEntity previousAssistant = scopedMessage(tenantId, ownerJiacn, clientId,
                    conversationId, messageId - 2, "ASSISTANT", "Previous target answer",
                    "{\"targetAgentId\":\"hosted-agent-contract\"}");
            history.add(previousAssistant);
            history.add(previousUser);
            currentText = "Inspect the fixed contract context";
        }
        ChatMessageEntity currentUser = scopedMessage(tenantId, ownerJiacn, clientId,
                conversationId, messageId, "USER", currentText, null);
        Map<String, Object> authorizedContext = invoke(service, "authorizedContext",
                new Class<?>[]{String.class, String.class, String.class, String.class, String.class,
                        List.class, ChatMessageEntity.class, List.class, Map.class},
                tenantId, ownerJiacn, clientId, conversationId, targetAgentId,
                history, currentUser, inputRefs, taskMaterials);
        Map<String, Object> sourceVector = invoke(service, "sourceVector",
                new Class<?>[]{long.class, long.class, AgentTaskDTO.class, Map.class},
                generation, messageId, task, authorizedContext);
        Map<String, Object> facts = invoke(service, "factsManifest",
                new Class<?>[]{ChatConversationEntity.class, JuyitingConversationScope.class,
                        String.class, AgentTaskDTO.class, Map.class, Map.class},
                conversation, scope, targetAgentId, task, taskMaterials, authorizedContext);
        String contextHash = ChatDeliberationService.contextDigest(sourceVector, facts);
        String turnId = stableId(service, "turn", tenantId, ownerJiacn, clientId, requestId, targetAgentId);
        String dispatchId = stableId(service, "dispatch", tenantId, ownerJiacn, clientId, requestId,
                targetAgentId);
        String snapshotId = stableId(service, "ctx", turnId, contextHash);
        String eventId = stableId(service, "evt", dispatchId, "DISPATCH");

        ChatMessageDTO input = new ChatMessageDTO();
        input.setSenderType("agent");
        input.setSenderName("伪造代理身份");
        input.setMetadata(Map.of(
                "schemaVersion", "1",
                "correlationId", "correlation-contract-v1",
                "participantAgentIds", List.of(targetAgentId, "builtin-songjiang"),
                "senderType", "agent",
                "senderName", "伪造代理身份",
                "untrustedIgnored", "must-not-appear"));
        Map<String, Object> eventPayload = service.eventPayload(
                tenantId, ownerJiacn, clientId, conversationId, generation, requestId,
                requestRevision, turnId, dispatchId, targetAgentId, snapshotId, contextHash,
                longHistory ? InteractionRoute.CHAT : InteractionRoute.INSPECT, currentText, input, scope,
                new ServerResolvedSender(ServerResolvedSender.USER_TYPE, "契约用户",
                        ownerJiacn, clientId, DisplayNameSource.NICKNAME),
                taskMaterials, occurredAt, sourceVector, facts);

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

    private static ChatMessageEntity scopedMessage(String tenantId, String ownerJiacn,
            String clientId, String conversationId, long id, String type, String content, String metadata) {
        ChatMessageEntity message = new ChatMessageEntity().setId(id).setConversationId(conversationId)
                .setMessageType(type).setContent(content).setMetadata(metadata).setJiacn(ownerJiacn);
        message.setTenantId(tenantId);
        message.setClientId(clientId);
        return message;
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
