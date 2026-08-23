package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandWireNormalizerContractTest {
    @Test
    void d02WireBytesNormalizeAsTheOnlyExecutableCommandType() throws Exception {
        String commandId = AgentCommandCanonicalCodec.taskInviteCommandId(
                "tenant-a", "client-a", "task-1", "agent-1");
        AgentCommandDraft draft = new AgentCommandDraft(
                1, commandId, "task-1", "evt-real", "tenant-a", "client-a", "task-1",
                null, "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE, 1000L, 3601000L,
                new AgentTaskInvitePayload(
                        "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                        "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                        "Task One", List.of("analysis", "planning"), "agent-1",
                        List.of("agent-1", "agent-2"), "coordinator",
                        "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                        "juyiting"));
        byte[] wireBytes = AgentCommandCanonicalCodec.wireBytes(
                draft, "00000000-0000-0000-0000-000000000001");
        Map<String, Object> wire = new ObjectMapper().readValue(
                new String(wireBytes, StandardCharsets.UTF_8), new TypeReference<>() { });

        AgentProtocolMessageNormalizer normalizer = new AgentProtocolMessageNormalizer();
        AgentProtocolMessageNormalizer.NormalizedMessage normalized = normalizer.normalizeInbound(wire);
        AgentProtocolMessageNormalizer.NormalizedMessage taskEvent = normalizer.normalizeInbound(Map.of(
                "schemaVersion", AgentProtocolConstants.VERSION_1,
                "messageType", AgentProtocolConstants.TYPE_TASK_EVENT,
                "messageId", "event-1",
                "taskId", "task-1"));

        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, wire.get("messageType"));
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, normalized.canonicalType());
        assertTrue(normalized.executionTrigger());
        assertEquals(AgentProtocolConstants.TYPE_TASK_EVENT, taskEvent.canonicalType());
        assertFalse(taskEvent.executionTrigger());
    }
}
