package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandCanonicalCodecTest {
    @Test
    void freezesDeterministicIdentityCanonicalFieldOrderAndExactHashes() {
        AgentCommandDraft draft = draft("Task One");
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
        assertTrue(wireJson.startsWith(
                "{\"schemaVersion\":1,\"messageType\":\"command.dispatch\",\"messageId\":"));
        assertEquals(1, occurrences(wireJson, "\"messageType\":\"command.dispatch\""));
        assertTrue(wireJson.contains("\"workItemId\":null"));
        assertTrue(wireJson.indexOf("\"attempt\":1") < wireJson.indexOf("\"payload\":"));
        assertEquals(List.of("analysis", "planning"), draft.payload().requiredAbilities());
        assertEquals(List.of("agent-1", "agent-2"), draft.payload().collaboratorAgentIds());
    }

    @Test
    void payloadWhitelistRejectsCredentialLikeContentAndNonCanonicalArrays() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandCanonicalCodec.businessBytes(draft("Authorization: Bearer secret")));
        AgentCommandDraft nonCanonical = new AgentCommandDraft(
                1, commandId(), "task-1", "evt-real", "tenant-a", "client-a", "task-1",
                null, "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE, 1000L, 3601000L,
                payload("Task One", List.of("planning", "analysis"),
                        List.of("agent-2", "agent-1")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandCanonicalCodec.businessBytes(nonCanonical));
    }

    private AgentCommandDraft draft(String title) {
        return new AgentCommandDraft(
                1, commandId(), "task-1", "evt-real", "tenant-a", "client-a", "task-1",
                null, "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE, 1000L, 3601000L,
                payload(title, List.of("analysis", "planning"), List.of("agent-1", "agent-2")));
    }

    private AgentTaskInvitePayload payload(
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

    private String commandId() {
        return AgentCommandCanonicalCodec.taskInviteCommandId(
                "tenant-a", "client-a", "task-1", "agent-1");
    }
}
