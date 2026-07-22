package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProtocolMessageNormalizerTest {
    private final AgentProtocolMessageNormalizer normalizer = new AgentProtocolMessageNormalizer();

    @Test
    void normalizesLegacyChatAndActionAliases() {
        AgentProtocolMessageNormalizer.NormalizedMessage chat = normalizer.normalizeInbound(Map.of(
                "type", "agent.message",
                "agentId", "agent-001",
                "conversationId", "conversation-1",
                "content", "hello"));
        AgentProtocolMessageNormalizer.NormalizedMessage command = normalizer.normalizeInbound(Map.of(
                "type", AgentProtocolConstants.LEGACY_AGENT_ACTION,
                "agentId", "agent-001",
                "actionType", "task_briefing"));

        assertEquals(AgentProtocolConstants.TYPE_CHAT_MESSAGE, chat.canonicalType());
        assertEquals(AgentProtocolConstants.CATEGORY_CHAT, chat.category());
        assertFalse(chat.executionTrigger());
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, command.canonicalType());
        assertEquals(AgentProtocolConstants.CATEGORY_COMMAND, command.category());
        assertTrue(command.executionTrigger());
    }

    @Test
    void acceptsCanonicalMessageTypeWithoutLegacyOuterType() {
        AgentProtocolMessageNormalizer.NormalizedMessage normalized = normalizer.normalizeInbound(Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                "messageId", "message-1",
                "sourceAgentId", "agent-001",
                "content", "hello"));

        assertEquals(AgentProtocolConstants.TYPE_CHAT_MESSAGE, normalized.canonicalType());
        assertFalse(normalized.legacy());
    }

    @Test
    void rejectsNonExactSchemaVersionsAndCanonicalV1WithoutVersion() {
        Map<String, Object> command = new HashMap<>();
        command.put("type", AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        command.put("messageId", "message-1");
        command.put("commandId", "command-1");
        command.put("commandType", AgentProtocolConstants.COMMAND_TASK_INVITE);
        command.put("targetAgentId", "agent-001");
        assertProtocolError("SCHEMA_VERSION_REQUIRED", command);

        Map<String, Object> overflow = new HashMap<>(command);
        overflow.put("schemaVersion", 4_294_967_297L);
        assertProtocolError("UNSUPPORTED_SCHEMA_VERSION", overflow);

        Map<String, Object> decimal = new HashMap<>(command);
        decimal.put("schemaVersion", 1.0d);
        assertProtocolError("INVALID_SCHEMA_VERSION", decimal);

        Map<String, Object> disguisedString = new HashMap<>(command);
        disguisedString.put("schemaVersion", "1");
        assertProtocolError("INVALID_SCHEMA_VERSION", disguisedString);
    }

    @Test
    void rejectsOuterAndNestedEnvelopeConflicts() {
        assertProtocolError("MESSAGE_TYPE_CONFLICT", Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "type", AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                "messageId", "message-1",
                "commandId", "command-1",
                "commandType", AgentProtocolConstants.COMMAND_TASK_INVITE,
                "targetAgentId", "agent-001",
                "payload", Map.of("messageType", AgentProtocolConstants.TYPE_TASK_EVENT)));

        assertProtocolError("ENVELOPE_FIELD_CONFLICT", Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "type", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                "messageId", "message-1",
                "sourceAgentId", "agent-001",
                "runtimeInstanceId", "runtime-outer",
                "payload", Map.of("runtimeInstanceId", "runtime-inner")));

        assertProtocolError("ENVELOPE_FIELD_CONFLICT", Map.of(
                "type", "agent.message",
                "agentId", "agent-outer",
                "payload", Map.of("sourceAgentId", "agent-inner")));

        assertProtocolError("ENVELOPE_FIELD_CONFLICT", Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "type", AgentProtocolConstants.TYPE_COMMAND_DISPATCH,
                "messageId", "message-1",
                "commandId", "command-outer",
                "commandType", AgentProtocolConstants.COMMAND_TASK_INVITE,
                "targetAgentId", "agent-001",
                "payload", Map.of("commandId", "command-inner")));

        assertProtocolError("ENVELOPE_FIELD_CONFLICT", Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "type", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                "messageId", "message-1",
                "sourceAgentId", "agent-001",
                "runtimeInstanceId", "runtime-1",
                "payload", Map.of("schemaVersion", AgentProtocolConstants.LEGACY_VERSION)));
    }

    @Test
    void rejectsConflictingMessageIdAliasesWithinAndAcrossLayers() {
        assertProtocolError("ENVELOPE_FIELD_CONFLICT", Map.of(
                "type", "agent.message",
                "messageId", "message-outer",
                "payload", Map.of("requestId", "message-inner")));

        assertProtocolError("ENVELOPE_FIELD_CONFLICT", Map.of(
                "type", "agent.message",
                "messageId", "message-1",
                "requestId", "request-2"));
    }

    @Test
    void acceptsMatchingMessageIdAliasesWithinAndAcrossLayers() {
        AgentProtocolMessageNormalizer.NormalizedMessage normalized = normalizer.normalizeInbound(Map.of(
                "type", "agent.message",
                "messageId", "message-1",
                "requestId", "message-1",
                "payload", Map.of(
                        "messageId", "message-1",
                        "requestId", "message-1")));

        assertEquals("message-1", normalized.envelope().getMessageId());
    }

    @Test
    void rejectsExplicitInvalidTypeDeclarationsInOuterAndPayloadLayers() {
        Map<String, Object> outerNull = new HashMap<>();
        outerNull.put("messageType", null);
        outerNull.put("payload", Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "messageType", AgentProtocolConstants.TYPE_TASK_EVENT,
                "messageId", "event-1"));
        assertProtocolError("INVALID_MESSAGE_TYPE", outerNull);

        Map<String, Object> nestedNull = new HashMap<>();
        nestedNull.put("messageType", null);
        assertProtocolError("INVALID_MESSAGE_TYPE", Map.of(
                "type", "agent.message",
                "payload", nestedNull));

        assertProtocolError("INVALID_MESSAGE_TYPE", Map.of(
                "type", 1,
                "payload", Map.of("messageType", "agent.message")));
        assertProtocolError("INVALID_MESSAGE_TYPE", Map.of(
                "type", "agent.message",
                "payload", Map.of("messageType", 1)));
        assertProtocolError("INVALID_MESSAGE_TYPE", Map.of(
                "messageType", "   ",
                "payload", Map.of("type", "agent.message")));
        assertProtocolError("INVALID_MESSAGE_TYPE", Map.of(
                "type", "agent.message",
                "payload", Map.of("type", "\t")));
    }

    @Test
    void rejectsDirectCompatibilityWrapperForEventSemantics() {
        assertProtocolError("MESSAGE_TYPE_CONFLICT", Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "type", AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE,
                "payload", Map.of(
                        "messageType", AgentProtocolConstants.TYPE_TASK_EVENT,
                        "messageId", "event-1")));
    }

    @Test
    void rejectsConflictingLegacyAndCanonicalMessageTypes() {
        AgentProtocolMessageNormalizer.AgentProtocolException error = assertThrows(
                AgentProtocolMessageNormalizer.AgentProtocolException.class,
                () -> normalizer.normalizeInbound(Map.of(
                        "type", AgentProtocolConstants.LEGACY_TASK_EVENT,
                        "messageType", AgentProtocolConstants.TYPE_COMMAND_DISPATCH)));

        assertEquals("MESSAGE_TYPE_CONFLICT", error.getCode());
    }

    @Test
    void taskEventIsAnEventAndNeverAnExecutionTrigger() {
        AgentProtocolMessageNormalizer.NormalizedMessage normalized = normalizer.normalizeInbound(Map.of(
                "type", AgentProtocolConstants.LEGACY_TASK_EVENT,
                "taskId", "task-001"));

        assertEquals(AgentProtocolConstants.TYPE_TASK_EVENT, normalized.canonicalType());
        assertEquals(AgentProtocolConstants.CATEGORY_EVENT, normalized.category());
        assertFalse(normalized.executionTrigger());
    }

    @Test
    void legacyTaskReportUsesOnlyTheLegacyResultAdapter() {
        AgentProtocolMessageNormalizer.NormalizedMessage normalized = normalizer.normalizeInbound(Map.of(
                "type", AgentProtocolConstants.LEGACY_TASK_REPORT,
                "taskId", "task-001",
                "agentId", "agent-001"));

        assertEquals(AgentProtocolConstants.TYPE_WORK_RESULT, normalized.canonicalType());
        assertEquals(AgentProtocolConstants.CATEGORY_RESULT, normalized.category());
        assertTrue(normalized.legacyTaskReport());
        assertFalse(normalized.executionTrigger());

        AgentProtocolMessageNormalizer.NormalizedMessage codexResult = normalizer.normalizeInbound(Map.of(
                "type", AgentProtocolConstants.LEGACY_CODEX_RESULT,
                "taskId", "task-001",
                "agentId", "agent-001"));
        assertEquals(AgentProtocolConstants.TYPE_WORK_RESULT, codexResult.canonicalType());
        assertFalse(codexResult.legacyTaskReport());
    }

    @Test
    void rejectsIncompleteProtocolV1CommandEnvelope() {
        Map<String, Object> base = new HashMap<>();
        base.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        base.put("type", AgentProtocolConstants.TYPE_COMMAND_DISPATCH);
        base.put("messageId", "message-1");
        base.put("commandId", "command-1");
        base.put("commandType", AgentProtocolConstants.COMMAND_TASK_INVITE);
        base.put("targetAgentId", "agent-001");

        Map<String, Object> withoutCommandId = new HashMap<>(base);
        withoutCommandId.remove("commandId");
        assertProtocolError("COMMAND_ID_REQUIRED", withoutCommandId);

        Map<String, Object> withoutCommandType = new HashMap<>(base);
        withoutCommandType.remove("commandType");
        assertProtocolError("COMMAND_TYPE_REQUIRED", withoutCommandType);

        Map<String, Object> withoutTarget = new HashMap<>(base);
        withoutTarget.remove("targetAgentId");
        assertProtocolError("TARGET_AGENT_ID_REQUIRED", withoutTarget);
    }

    @Test
    void rejectsConflictingCanonicalAgentIdentity() {
        AgentProtocolMessageNormalizer.AgentProtocolException error = assertThrows(
                AgentProtocolMessageNormalizer.AgentProtocolException.class,
                () -> normalizer.normalizeInbound(Map.of(
                        "type", "agent.message",
                        "agentId", "agent-001",
                        "sourceAgentId", "agent-002")));

        assertEquals("AGENT_ID_CONFLICT", error.getCode());
    }

    @Test
    void requiresRuntimeInstanceIdForProtocolV1Registration() {
        AgentProtocolMessageNormalizer.AgentProtocolException error = assertThrows(
                AgentProtocolMessageNormalizer.AgentProtocolException.class,
                () -> normalizer.normalizeInbound(Map.of(
                        "schemaVersion", AgentProtocolConstants.VERSION_1,
                        "type", AgentProtocolConstants.TYPE_AGENT_REGISTER,
                        "sourceAgentId", "agent-001")));

        assertEquals("RUNTIME_INSTANCE_ID_REQUIRED", error.getCode());
    }

    @Test
    void rejectsRuntimeInstanceIdThatAliasesCanonicalAgentId() {
        AgentProtocolMessageNormalizer.AgentProtocolException error = assertThrows(
                AgentProtocolMessageNormalizer.AgentProtocolException.class,
                () -> normalizer.normalizeInbound(Map.of(
                        "schemaVersion", AgentProtocolConstants.VERSION_1,
                        "type", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                        "messageId", "message-1",
                        "sourceAgentId", "agent-001",
                        "runtimeInstanceId", "agent-001")));

        assertEquals("RUNTIME_INSTANCE_ID_INVALID", error.getCode());
    }

    @Test
    void commandDispatchIsTheOnlyExecutionTrigger() {
        assertTrue(AgentProtocolConstants.isExecutionTrigger(AgentProtocolConstants.TYPE_COMMAND_DISPATCH));
        assertFalse(AgentProtocolConstants.isExecutionTrigger(AgentProtocolConstants.TYPE_CHAT_MESSAGE));
        assertFalse(AgentProtocolConstants.isExecutionTrigger(AgentProtocolConstants.TYPE_WORK_PROGRESS));
        assertFalse(AgentProtocolConstants.isExecutionTrigger(AgentProtocolConstants.TYPE_WORK_RESULT));
        assertFalse(AgentProtocolConstants.isExecutionTrigger(AgentProtocolConstants.TYPE_TASK_EVENT));
    }

    private void assertProtocolError(String expectedCode, Map<String, Object> message) {
        AgentProtocolMessageNormalizer.AgentProtocolException error = assertThrows(
                AgentProtocolMessageNormalizer.AgentProtocolException.class,
                () -> normalizer.normalizeInbound(message));
        assertEquals(expectedCode, error.getCode());
    }
}
