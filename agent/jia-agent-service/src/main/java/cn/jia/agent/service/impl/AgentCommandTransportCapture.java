package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputRunRequest;
import cn.jia.agent.output.dto.OutputContextDTO;
import cn.jia.agent.service.AgentCommandTransportWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ObjectProvider<OutputRunAuthorizationService> outputRunProvider;
    private final boolean compatibilityDisabled;
    private AgentTaskWorkItemDao workItemDao;

    public record PreparedTaskOutputContexts(
            Map<String, OutputContextDTO> contexts,
            Map<String, String> workItemIds) {
        public PreparedTaskOutputContexts {
            contexts = contexts == null ? Map.of() : Map.copyOf(contexts);
            workItemIds = workItemIds == null ? Map.of() : Map.copyOf(workItemIds);
        }
    }

    public AgentCommandTransportCapture(
            AgentRabbitSafetyGate gate,
            ObjectProvider<AgentCommandTransportWriter> writerProvider) {
        this(gate, writerProvider, null);
    }

    @Autowired
    public AgentCommandTransportCapture(
            AgentRabbitSafetyGate gate,
            ObjectProvider<AgentCommandTransportWriter> writerProvider,
            ObjectProvider<OutputRunAuthorizationService> outputRunProvider) {
        this.gate = gate;
        this.writerProvider = writerProvider;
        this.outputRunProvider = outputRunProvider;
        this.compatibilityDisabled = false;
    }

    private AgentCommandTransportCapture() {
        this.gate = null;
        this.writerProvider = null;
        this.outputRunProvider = null;
        this.compatibilityDisabled = true;
    }

    static AgentCommandTransportCapture disabledForLegacyConstruction() {
        return new AgentCommandTransportCapture();
    }

    @Autowired
    void configureWorkItemDao(ObjectProvider<AgentTaskWorkItemDao> provider) {
        this.workItemDao = provider == null ? null : provider.getIfAvailable();
    }

    public boolean captureTaskInvites(
            AgentTaskDTO task,
            List<AgentRuntimeEntity> assignedAgents,
            String taskAssignedEventId,
            long occurredAt) {
        return captureTaskInvites(
                task, assignedAgents, taskAssignedEventId, occurredAt, Map.of());
    }

    public Map<String, OutputContextDTO> prepareTaskOutputContexts(
            String tenantId, String clientId, String taskId, List<String> targetAgentIds) {
        return prepareTaskOutputContexts(tenantId, clientId, taskId, targetAgentIds, 0);
    }

    public Map<String, OutputContextDTO> prepareTaskOutputContexts(
            String tenantId, String clientId, String taskId, List<String> targetAgentIds,
            int policyVersion) {
        return prepareTaskOutputDispatch(
                tenantId, clientId, taskId, targetAgentIds, policyVersion).contexts();
    }

    public PreparedTaskOutputContexts prepareTaskOutputDispatch(
            String tenantId, String clientId, String taskId, List<String> targetAgentIds,
            int policyVersion) {
        if (compatibilityDisabled || !gate.commandOutboxEnabled() || outputRunProvider == null) {
            return new PreparedTaskOutputContexts(Map.of(), Map.of());
        }
        OutputRunAuthorizationService service = outputRunProvider.getIfAvailable();
        if (service == null) return new PreparedTaskOutputContexts(Map.of(), Map.of());
        List<String> orderedTargets = new ArrayList<>(targetAgentIds);
        orderedTargets.sort(UTF8_ORDER);
        AgentTaskWorkItemEntity policyItem = null;
        if (policyVersion == 1) {
            if (orderedTargets.size() != 1 || workItemDao == null) {
                throw new IllegalStateException(
                        "Delivery policy 1 requires one target and the work-item DAO");
            }
            List<AgentTaskWorkItemEntity> items = workItemDao.listByTask(
                    tenantId, clientId, taskId, null, 2);
            if (items == null || items.size() != 1
                    || !Boolean.TRUE.equals(items.getFirst().getRequiredItem())
                    || !orderedTargets.getFirst().equals(items.getFirst().getAssigneeAgentId())
                    || !"ready".equals(items.getFirst().getStatus())
                    || items.getFirst().getVersion() == null
                    || items.getFirst().getDispatchedRunId() != null
                    || items.getFirst().getExecutionRunId() != null) {
                throw new IllegalStateException(
                        "Delivery policy 1 requires one undispatched required work item");
            }
            policyItem = items.getFirst();
        } else if (policyVersion != 0) {
            throw new IllegalStateException("Unsupported task delivery policy");
        }
        String workItemId = policyItem == null ? null : policyItem.getWorkItemId();
        List<OutputRunRequest> requests = orderedTargets.stream()
                .map(agentId -> new OutputRunRequest(
                        tenantId, clientId, OutputConstants.SOURCE_TASK, taskId,
                        agentId, "COMMAND", AgentCommandCanonicalCodec.taskInviteCommandId(
                                tenantId, clientId, taskId, agentId), workItemId, policyVersion))
                .toList();
        Map<String, OutputContextDTO> contexts = service.createOrRecoverRuns(requests, orderedTargets);
        Map<String, String> workItemIds = new LinkedHashMap<>();
        if (policyItem != null) {
            OutputContextDTO context = contexts.get(orderedTargets.getFirst());
            if (context == null || context.runId() == null
                    || !context.runId().matches("[0-9a-f]{32}")) {
                throw new IllegalStateException("Policy-1 dispatch did not create a trusted run");
            }
            if (workItemDao.bindDispatchedRun(tenantId, clientId, taskId,
                    policyItem.getWorkItemId(), orderedTargets.getFirst(),
                    policyItem.getVersion(), context.runId()) != 1) {
                throw new IllegalStateException("Policy-1 work item dispatch binding failed");
            }
            workItemIds.put(orderedTargets.getFirst(), policyItem.getWorkItemId());
        }
        return new PreparedTaskOutputContexts(contexts, workItemIds);
    }

    public boolean captureTaskInvites(
            AgentTaskDTO task,
            List<AgentRuntimeEntity> assignedAgents,
            String taskAssignedEventId,
            long occurredAt,
            Map<String, OutputContextDTO> outputContexts) {
        return captureTaskInvites(task, assignedAgents, taskAssignedEventId,
                occurredAt, outputContexts, Map.of());
    }

    public boolean captureTaskInvites(
            AgentTaskDTO task,
            List<AgentRuntimeEntity> assignedAgents,
            String taskAssignedEventId,
            long occurredAt,
            Map<String, OutputContextDTO> outputContexts,
            Map<String, String> workItemIds) {
        if (compatibilityDisabled || !gate.commandOutboxEnabled()) return false;
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
            OutputContextDTO outputContext = outputContexts.get(targetAgentId);
            String workItemId = workItemIds.get(targetAgentId);
            boolean deliveryRun = outputContext != null && outputContext.capabilities()
                    .contains(OutputConstants.CAPABILITY_DELIVERY_HTTP_V1);
            if (deliveryRun != (workItemId != null)
                    || workItemId != null && (workItemId.isBlank()
                    || !workItemId.equals(workItemId.strip())
                    || workItemId.length() > 100
                    || workItemId.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalStateException(
                        "Delivery command requires its trusted work-item identity");
            }
            AgentTaskInvitePayload payload = new AgentTaskInvitePayload(
                    ACTION_TYPE, REASON, INSTRUCTION, title, abilities, coordinator,
                    collaboratorIds, targetAgentId.equals(coordinator) ? "coordinator" : "worker",
                    ACCEPTANCE, "juyiting");
            writer.write(new AgentCommandDraft(
                    AgentCommandCanonicalCodec.SCHEMA_VERSION,
                    AgentCommandCanonicalCodec.taskInviteCommandId(
                            task.getTenantId(), task.getClientId(), task.getId(), targetAgentId),
                    task.getId(), taskAssignedEventId,
                    task.getTenantId(), task.getClientId(), task.getId(), workItemId,
                    targetAgentId, AgentProtocolConstants.COMMAND_TASK_INVITE,
                    occurredAt, expiresAt, payload), outputContexts.get(targetAgentId));
        }
        return gate.allowsDispatch(task.getTenantId(), task.getClientId());
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
