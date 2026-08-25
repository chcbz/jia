package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentHallCommandContext;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandCanonicalCodecTest {
    @Test
    void taskInviteGoldenBytesAndHashesRemainByteExact() {
        AgentCommandDraft draft = taskInviteDraft("Task One");
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(
                draft, "00000000-0000-0000-0000-000000000001");
        String businessJson = new String(business, StandardCharsets.UTF_8);
        String wireJson = new String(wire, StandardCharsets.UTF_8);

        assertEquals("cmd_task_invite_3911efee900c2fec5eb524e7822f03ddefaf978a4d233bdb0c10b2ac701dcda2",
                draft.commandId());
        assertEquals("9024792c9ec38a686c7b60781ea7d416524602d1c545b94f8e06ce5737a8d592",
                HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(business)));
        assertEquals("387a112c3c0de1c7799bc5d38140915313d36f11b62aab1ef58369a2ada85089",
                HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(wire)));
        assertTrue(businessJson.startsWith("{\"schemaVersion\":1,\"commandId\":"));
        assertFalse(businessJson.contains("messageId"));
        assertFalse(businessJson.contains("messageType"));
        assertFalse(businessJson.contains("\"attempt\""));
        assertFalse(businessJson.contains("\"intentId\""));
        assertTrue(wireJson.startsWith(
                "{\"schemaVersion\":1,\"messageType\":\"command.dispatch\",\"messageId\":"));
        assertEquals(1, occurrences(wireJson, "\"messageType\":\"command.dispatch\""));
        assertTrue(wireJson.contains("\"workItemId\":null"));
        assertTrue(wireJson.indexOf("\"attempt\":1") < wireJson.indexOf("\"payload\":"));
        AgentTaskInvitePayload payload = assertInstanceOf(
                AgentTaskInvitePayload.class, draft.payload());
        assertEquals(List.of("analysis", "planning"), payload.requiredAbilities());
        assertEquals(List.of("agent-1", "agent-2"), payload.collaboratorAgentIds());
        assertArrayEquals(business,
                AgentCommandCanonicalCodec.businessBytes(
                        AgentCommandCanonicalCodec.decodeBusinessBytes(business)));
    }

    @Test
    void hallGoldenIdentityTtlAndTypedPayloadAreFrozen() {
        AgentCommandDraft draft = hallDraft(
                "execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                1000L, "执行当前工作项并回报结果", "evt-1", canonicalContext());
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        String json = new String(business, StandardCharsets.UTF_8);

        assertEquals("cmd_hall_action_002bffc36b1e33031655b9bfef134424507f44027d78e3efd2cbe7ecc92b0226",
                draft.commandId());
        assertEquals("d504a0855c1dffc66e1cb8ae487a0962a2fa3bd29fe8b9a35a47a51f7a3a2674",
                HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(business)));
        assertEquals("task-1", draft.correlationId());
        assertEquals("evt-1", draft.causationId());
        assertEquals(AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS,
                draft.expiresAt() - draft.issuedAt());
        assertTrue(json.indexOf("\"expiresAt\":3601000") < json.indexOf("\"intentId\":"));
        assertTrue(json.indexOf("\"intentId\":\"intent-1\"") < json.indexOf("\"payload\":"));
        AgentCommandDraft decoded = AgentCommandCanonicalCodec.decodeBusinessBytes(business);
        assertEquals(draft, decoded);
        assertInstanceOf(AgentHallCommandPayload.class, decoded.payload());
    }

    @Test
    void acceptsEveryFrozenHallCommandTypeWithoutTaskInviteMasquerading() {
        List<ActionType> allowlist = List.of(
                new ActionType("execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE),
                new ActionType("resume", AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME),
                new ActionType("cancel", AgentProtocolConstants.COMMAND_WORK_ITEM_CANCEL),
                new ActionType("ask_help", AgentProtocolConstants.COMMAND_REQUEST_RESPOND),
                new ActionType("review", AgentProtocolConstants.COMMAND_REVIEW_EXECUTE),
                new ActionType("request_report", AgentProtocolConstants.COMMAND_CONTEXT_REFRESH));

        for (ActionType item : allowlist) {
            AgentCommandDraft draft = hallDraft(
                    item.actionType(), item.commandType(), 1000L,
                    "执行该聚义厅动作并回报结果", null, null);
            byte[] bytes = AgentCommandCanonicalCodec.businessBytes(draft);
            AgentCommandDraft decoded = AgentCommandCanonicalCodec.decodeBusinessBytes(bytes);
            assertEquals(item.commandType(), decoded.commandType());
            assertFalse(AgentProtocolConstants.COMMAND_TASK_INVITE.equals(decoded.commandType()));
            assertEquals("intent-1", decoded.causationId());
            assertInstanceOf(AgentHallCommandPayload.class, decoded.payload());
        }
    }

    @Test
    void rejectsUnknownDuplicateNestedOversizedAndCredentialContent() {
        AgentCommandDraft draft = hallDraft(
                "execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                1000L, "执行当前工作项并回报结果", "evt-1", canonicalContext());
        String canonical = new String(
                AgentCommandCanonicalCodec.businessBytes(draft), StandardCharsets.UTF_8);
        String unknownEnvelope = canonical.substring(0, canonical.length() - 1)
                + ",\"unknown\":true}";
        String duplicateTask = canonical.replace(
                "\"taskId\":\"task-1\"",
                "\"taskId\":\"task-1\",\"taskId\":\"task-1\"");
        String unknownOptionalReplacement = canonical.replace(
                "\"requiresApproval\":true", "\"credential\":true");
        String nestedContext = canonical.replace(
                "\"taskTitle\":\"Task One\"",
                "\"taskTitle\":{\"nested\":\"Task One\"}");

        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.decodeBusinessBytes(
                unknownEnvelope.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.decodeBusinessBytes(
                duplicateTask.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.decodeBusinessBytes(
                unknownOptionalReplacement.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.decodeBusinessBytes(
                nestedContext.getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.decodeBusinessBytes(
                new byte[AgentCommandCanonicalCodec.MAX_CANONICAL_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.businessBytes(
                hallDraft("execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                        1000L, "Authorization: Bearer secret", "evt-1", canonicalContext())));
        AgentHallCommandContext secretContext = new AgentHallCommandContext(
                "Task One", null, null, null, "token=secret", List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.businessBytes(
                hallDraft("execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                        1000L, "执行当前工作项并回报结果", "evt-1", secretContext)));
    }

    @Test
    void rejectsActionIdentityCausationAndTtlConflicts() {
        AgentCommandDraft valid = hallDraft(
                "execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                1000L, "执行当前工作项并回报结果", "evt-1", null);
        AgentHallCommandPayload payload = (AgentHallCommandPayload) valid.payload();
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.businessBytes(
                new AgentCommandDraft(valid.schemaVersion(), valid.commandId(), valid.correlationId(),
                        valid.causationId(), valid.tenantId(), valid.clientId(), valid.taskId(),
                        valid.workItemId(), valid.targetAgentId(),
                        AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME,
                        valid.issuedAt(), valid.expiresAt(), valid.intentId(), payload)));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.businessBytes(
                new AgentCommandDraft(valid.schemaVersion(), valid.commandId(), valid.correlationId(),
                        "evt-forged", valid.tenantId(), valid.clientId(), valid.taskId(),
                        valid.workItemId(), valid.targetAgentId(), valid.commandType(),
                        valid.issuedAt(), valid.expiresAt(), valid.intentId(), payload)));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.businessBytes(
                new AgentCommandDraft(valid.schemaVersion(), valid.commandId(), valid.correlationId(),
                        valid.causationId(), valid.tenantId(), valid.clientId(), valid.taskId(),
                        valid.workItemId(), valid.targetAgentId(), valid.commandType(),
                        valid.issuedAt(), valid.expiresAt() + 1, valid.intentId(), payload)));
        assertThrows(IllegalArgumentException.class, () -> AgentCommandCanonicalCodec.businessBytes(
                hallDraft("unknown-action", AgentProtocolConstants.COMMAND_CONTEXT_REFRESH,
                        1000L, "执行当前工作项并回报结果", null, null)));
    }

    @Test
    void taskInviteWhitelistStillRejectsCredentialAndNonCanonicalArrays() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandCanonicalCodec.businessBytes(
                        taskInviteDraft("Authorization: Bearer secret")));
        AgentCommandDraft nonCanonical = new AgentCommandDraft(
                1, taskInviteCommandId(), "task-1", "evt-real", "tenant-a", "client-a", "task-1",
                null, "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE, 1000L, 3601000L,
                taskInvitePayload("Task One", List.of("planning", "analysis"),
                        List.of("agent-2", "agent-1")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandCanonicalCodec.businessBytes(nonCanonical));
    }

    private AgentCommandDraft hallDraft(
            String actionType, String commandType, long issuedAt, String instruction,
            String triggerEventId, AgentHallCommandContext context) {
        String intentId = "intent-1";
        return new AgentCommandDraft(
                1, AgentCommandCanonicalCodec.hallCommandId(
                        "tenant-a", "client-a", "task-1", "agent-1", intentId, commandType),
                "task-1", triggerEventId == null ? intentId : triggerEventId,
                "tenant-a", "client-a", "task-1", "work-1", "agent-1", commandType,
                issuedAt, issuedAt + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS,
                intentId, new AgentHallCommandPayload(
                        actionType, instruction, "juyiting", "ready", "conversation-1",
                        triggerEventId, "supervised", true, context));
    }

    private AgentHallCommandContext canonicalContext() {
        return new AgentHallCommandContext(
                "Task One", "Work One", null, null, "v1",
                List.of("ref-a", "ref-b"), List.of("alpha"));
    }

    private AgentCommandDraft taskInviteDraft(String title) {
        return new AgentCommandDraft(
                1, taskInviteCommandId(), "task-1", "evt-real", "tenant-a", "client-a", "task-1",
                null, "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE, 1000L, 3601000L,
                taskInvitePayload(title, List.of("analysis", "planning"),
                        List.of("agent-1", "agent-2")));
    }

    private AgentTaskInvitePayload taskInvitePayload(
            String title, List<String> abilities, List<String> collaborators) {
        return new AgentTaskInvitePayload(
                "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                title, abilities, "agent-1", collaborators, "coordinator",
                "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                "juyiting");
    }

    private int occurrences(String value, String expected) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(expected, offset)) >= 0) {
            count++;
            offset += expected.length();
        }
        return count;
    }

    private String taskInviteCommandId() {
        return AgentCommandCanonicalCodec.taskInviteCommandId(
                "tenant-a", "client-a", "task-1", "agent-1");
    }

    private record ActionType(String actionType, String commandType) {
    }
}
