package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputRunRequest;
import cn.jia.agent.output.dto.OutputContextDTO;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.core.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private static final String DELIVERY_ACCEPTANCE =
            "先领取并启动租约，执行期间续租；成果发布后使用正式交付接口提交，禁止使用旧 work.result 完成任务。";
    private static final Comparator<String> UTF8_ORDER = AgentCommandTransportCapture::compareUtf8Unsigned;

    private final AgentRabbitSafetyGate gate;
    private final ObjectProvider<AgentCommandTransportWriter> writerProvider;
    private final ObjectProvider<OutputRunAuthorizationService> outputRunProvider;
    private final boolean compatibilityDisabled;
    private AgentTaskWorkItemDao workItemDao;
    private AgentRabbitTopologyReadiness topologyReadiness;

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

    @Autowired
    void configureTopologyReadiness(
            @Qualifier("agentRabbitTopologyReadiness")
            ObjectProvider<AgentRabbitTopologyReadiness> provider) {
        this.topologyReadiness = provider == null ? null : provider.getIfAvailable();
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
        return prepareTaskOutputDispatch(
                tenantId, clientId, taskId, targetAgentIds, policyVersion, null);
    }

    public PreparedTaskOutputContexts prepareTaskOutputDispatch(
            String tenantId, String clientId, String taskId, List<String> targetAgentIds,
            int policyVersion, String dispatchIdentity) {
        if (policyVersion == 1) {
            requirePolicy1DispatchAvailable(tenantId, clientId);
            if (dispatchIdentity == null || dispatchIdentity.isBlank()) {
                throw new IllegalStateException(
                        "Delivery policy 1 requires a durable dispatch identity");
            }
        }
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
                        agentId, "COMMAND", taskInviteCommandId(
                                tenantId, clientId, taskId, agentId,
                                workItemId, dispatchIdentity), workItemId, policyVersion))
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

    public void requirePolicy1DispatchAvailable(
            String tenantId, String clientId) {
        if (compatibilityDisabled || gate == null || !gate.commandOutboxEnabled()
                || !gate.allowsDispatch(tenantId, clientId)
                || topologyReadiness == null
                || !topologyReadiness.snapshot().canonicalTopologyReady()
                || writerProvider == null || writerProvider.getIfAvailable() == null
                || outputRunProvider == null || outputRunProvider.getIfAvailable() == null
                || workItemDao == null) {
            throw new IllegalStateException(
                    "Delivery policy 1 requires trusted command dispatch for the exact scope");
        }
    }

    public void redispatchPolicy1WorkItem(
            AgentTaskMetaEntity taskRoot, String workItemId, String targetAgentId,
            String causationEventId, long occurredAt) {
        if (taskRoot == null || taskRoot.getDeliveryPolicyVersion() == null
                || taskRoot.getDeliveryPolicyVersion() != 1
                || taskRoot.getTenantId() == null || taskRoot.getClientId() == null
                || taskRoot.getTaskId() == null) {
            throw new IllegalStateException("Policy-1 redispatch requires the locked task root");
        }
        requirePolicy1DispatchAvailable(taskRoot.getTenantId(), taskRoot.getClientId());
        PreparedTaskOutputContexts prepared = prepareTaskOutputDispatch(
                taskRoot.getTenantId(), taskRoot.getClientId(), taskRoot.getTaskId(),
                List.of(targetAgentId), 1, causationEventId);
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId(taskRoot.getTaskId());
        task.setTenantId(taskRoot.getTenantId());
        task.setClientId(taskRoot.getClientId());
        task.setAssignedAgentId(targetAgentId);
        task.setAssignedAgentIds(List.of(targetAgentId));
        task.setDeliveryPolicyVersion("1");
        task.setRequiredAbilities(taskRoot.getRequiredAbilities() == null
                ? List.of() : JsonUtil.jsonToList(taskRoot.getRequiredAbilities(), String.class));
        AgentRuntimeEntity target = new AgentRuntimeEntity().setAgentId(targetAgentId);
        boolean durable = captureTaskInvites(
                task, List.of(target), causationEventId, occurredAt,
                prepared.contexts(), prepared.workItemIds());
        if (!durable || !workItemId.equals(prepared.workItemIds().get(targetAgentId))) {
            throw new IllegalStateException("Policy-1 work item redispatch was not durable");
        }
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
        boolean policy1 = task != null && "1".equals(task.getDeliveryPolicyVersion());
        if (task != null && task.getDeliveryPolicyVersion() != null
                && !"0".equals(task.getDeliveryPolicyVersion()) && !policy1) {
            throw new IllegalStateException("Unsupported task delivery policy");
        }
        if (policy1) {
            requirePolicy1DispatchAvailable(task.getTenantId(), task.getClientId());
        }
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
            if (policy1 != deliveryRun || deliveryRun != (workItemId != null)
                    || workItemId != null && (workItemId.isBlank()
                    || !workItemId.equals(workItemId.strip())
                    || workItemId.length() > 100
                    || workItemId.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalStateException(
                        "Delivery command requires its trusted work-item identity");
            }
            if (deliveryRun) {
                AgentTaskWorkItemEntity item = workItemDao.findByTaskAndWorkItemId(
                        task.getTenantId(), task.getClientId(), task.getId(), workItemId);
                if (item == null || item.getVersion() == null
                        || !targetAgentId.equals(item.getAssigneeAgentId())
                        || !outputContext.runId().equals(item.getDispatchedRunId())
                        || item.getExecutionRunId() != null || !"ready".equals(item.getStatus())) {
                    throw new IllegalStateException(
                            "Delivery command does not match its dispatched work item");
                }
            }
            String commandId = taskInviteCommandId(
                    task.getTenantId(), task.getClientId(), task.getId(), targetAgentId,
                    workItemId, deliveryRun ? taskAssignedEventId : null);
            AgentTaskInvitePayload payload = new AgentTaskInvitePayload(
                    ACTION_TYPE, REASON, INSTRUCTION, title, abilities, coordinator,
                    collaboratorIds, targetAgentId.equals(coordinator) ? "coordinator" : "worker",
                    deliveryRun ? DELIVERY_ACCEPTANCE : ACCEPTANCE, "juyiting");
            writer.write(new AgentCommandDraft(
                    AgentCommandCanonicalCodec.SCHEMA_VERSION,
                    commandId,
                    task.getId(), taskAssignedEventId,
                    task.getTenantId(), task.getClientId(), task.getId(), workItemId,
                    targetAgentId, AgentProtocolConstants.COMMAND_TASK_INVITE,
                    occurredAt, expiresAt, payload), outputContexts.get(targetAgentId));
        }
        return policy1 || gate.allowsDispatch(task.getTenantId(), task.getClientId());
    }

    private static String taskInviteCommandId(
            String tenantId, String clientId, String taskId, String targetAgentId,
            String workItemId, String dispatchIdentity) {
        if (workItemId == null) {
            return AgentCommandCanonicalCodec.taskInviteCommandId(
                    tenantId, clientId, taskId, targetAgentId);
        }
        if (dispatchIdentity == null || dispatchIdentity.isBlank()) {
            throw new IllegalStateException("Policy-1 dispatch identity is invalid");
        }
        return AgentCommandCanonicalCodec.taskInviteCommandId(
                tenantId, clientId, taskId, targetAgentId,
                workItemId.length() + ":" + workItemId + ":" + dispatchIdentity);
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
