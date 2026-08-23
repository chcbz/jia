package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.service.AgentCommandTransportWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Assignment-only D02 producer boundary. Flags OFF returns before resolving the writer. */
@Service
public class AgentCommandTransportCapture {
    private static final String ACTION_TYPE = "task_briefing";
    private static final String REASON = "宋江首领已完成悬赏分派，请按职责协作推进。";
    private static final String INSTRUCTION = "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。";
    private static final String ACCEPTANCE =
            "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。";
    private static final Comparator<String> UTF8_ORDER = AgentCommandTransportCapture::compareUtf8Unsigned;

    private final AgentRabbitSafetyGate gate;
    private final ObjectProvider<AgentCommandTransportWriter> writerProvider;
    private final boolean compatibilityDisabled;

    public AgentCommandTransportCapture(
            AgentRabbitSafetyGate gate,
            ObjectProvider<AgentCommandTransportWriter> writerProvider) {
        this.gate = gate;
        this.writerProvider = writerProvider;
        this.compatibilityDisabled = false;
    }

    private AgentCommandTransportCapture() {
        this.gate = null;
        this.writerProvider = null;
        this.compatibilityDisabled = true;
    }

    static AgentCommandTransportCapture disabledForLegacyConstruction() {
        return new AgentCommandTransportCapture();
    }

    public void captureTaskInvites(
            AgentTaskDTO task,
            List<AgentRuntimeEntity> assignedAgents,
            String taskAssignedEventId,
            long occurredAt) {
        if (compatibilityDisabled || !gate.commandOutboxEnabled()) return;
        AgentCommandTransportWriter writer = writerProvider.getIfAvailable();
        if (writer == null) {
            throw new IllegalStateException(
                    "agent.command-outbox.enabled=true but AgentCommandTransportWriter is missing");
        }
        if (task == null || assignedAgents == null || assignedAgents.isEmpty()
                || taskAssignedEventId == null || taskAssignedEventId.isBlank()
                || occurredAt <= 0) {
            throw new IllegalStateException("Changed assignment lacks durable TASK_ASSIGNED command facts");
        }
        List<String> collaboratorIds = AgentCommandCanonicalCodec.canonicalCollaborators(
                assignedAgents.stream().map(AgentRuntimeEntity::getAgentId).toList());
        String coordinator = assignedAgents.getFirst().getAgentId();
        List<String> abilities = AgentCommandCanonicalCodec.canonicalAbilities(task.getRequiredAbilities());
        String title = task.getTitle() == null || task.getTitle().isBlank() ? task.getId() : task.getTitle();
        long expiresAt;
        try {
            expiresAt = Math.addExact(occurredAt, AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("TASK_INVITE expiry overflow", overflow);
        }
        List<AgentRuntimeEntity> targets = new ArrayList<>(assignedAgents);
        targets.sort(Comparator.comparing(AgentRuntimeEntity::getAgentId, UTF8_ORDER));
        for (AgentRuntimeEntity target : targets) {
            String targetAgentId = target.getAgentId();
            AgentTaskInvitePayload payload = new AgentTaskInvitePayload(
                    ACTION_TYPE, REASON, INSTRUCTION, title, abilities, coordinator,
                    collaboratorIds, targetAgentId.equals(coordinator) ? "coordinator" : "worker",
                    ACCEPTANCE, "juyiting");
            writer.write(new AgentCommandDraft(
                    AgentCommandCanonicalCodec.SCHEMA_VERSION,
                    AgentCommandCanonicalCodec.taskInviteCommandId(
                            task.getTenantId(), task.getClientId(), task.getId(), targetAgentId),
                    task.getId(), taskAssignedEventId,
                    task.getTenantId(), task.getClientId(), task.getId(), null,
                    targetAgentId, AgentProtocolConstants.COMMAND_TASK_INVITE,
                    occurredAt, expiresAt, payload));
        }
    }

    private static int compareUtf8Unsigned(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    }
}
