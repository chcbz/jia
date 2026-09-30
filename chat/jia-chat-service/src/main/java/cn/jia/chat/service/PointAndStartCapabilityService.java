package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import cn.jia.agent.service.NativeBountyExecutionSessionLookup;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Side-effect-free source projection; it never assigns, grants, claims, starts or calls a Provider. */
@Service
public final class PointAndStartCapabilityService {
    private static final String LANE = "ORDINARY_SINGLE_AGENT_ASSIGN_AND_START";
    private static final List<String> SERVER_OPERATIONS = List.of("GENERATE_IMAGE");
    private final NativeBountyExecutionSessionLookup sessions;
    private final AgentTaskPointAndStartPolicyService taskPolicy;
    private final PersonalWorkspaceStorage storage;
    private final ObjectProvider<ChatBountyBootstrapRelay> bootstrapRelay;
    private final ObjectProvider<ChatBountyExecutionRelay> executionRelay;
    private final Flags flags;

    public PointAndStartCapabilityService(NativeBountyExecutionSessionLookup sessions,
            AgentTaskPointAndStartPolicyService taskPolicy, PersonalWorkspaceStorage storage,
            ObjectProvider<ChatBountyBootstrapRelay> bootstrapRelay,
            ObjectProvider<ChatBountyExecutionRelay> executionRelay,
            @Value("${agent.personal-workspace-storage.enabled:false}") boolean storageEnabled,
            @Value("${jia.agent.conversation-execution.enabled:false}") boolean conversationEnabled,
            @Value("${jia.chat.service.websocket.enable:false}") boolean websocketEnabled,
            @Value("${chat.bounty-bootstrap.enabled:false}") boolean bootstrapEnabled,
            @Value("${chat.bounty-execution.enabled:false}") boolean executionEnabled,
            @Value("${agent.task-deliberation-operation.read-enabled:false}") boolean operationReadEnabled,
            @Value("${agent.task-requirement-snapshot.read-enabled:false}") boolean requirementReadEnabled) {
        this(sessions, taskPolicy, storage, bootstrapRelay, executionRelay,
                new Flags(storageEnabled, conversationEnabled, websocketEnabled,
                        bootstrapEnabled, executionEnabled, operationReadEnabled,
                        requirementReadEnabled));
    }

    PointAndStartCapabilityService(NativeBountyExecutionSessionLookup sessions,
            AgentTaskPointAndStartPolicyService taskPolicy, PersonalWorkspaceStorage storage,
            ObjectProvider<ChatBountyBootstrapRelay> bootstrapRelay,
            ObjectProvider<ChatBountyExecutionRelay> executionRelay, Flags flags) {
        this.sessions = Objects.requireNonNull(sessions);
        this.taskPolicy = Objects.requireNonNull(taskPolicy);
        this.storage = Objects.requireNonNull(storage);
        this.bootstrapRelay = Objects.requireNonNull(bootstrapRelay);
        this.executionRelay = Objects.requireNonNull(executionRelay);
        this.flags = Objects.requireNonNull(flags);
    }

    public Capability read(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String targetAgentId) {
        AgentTaskPointAndStartPolicyService.Snapshot policy = taskPolicy.read(
                scope, taskId, targetAgentId);
        requirePolicy(policy, taskId, targetAgentId);
        NativeBountyExecutionSessionLookup.Snapshot nativeExecution = sessions.current(
                new NativeBountyExecutionSessionLookup.Scope(scope.tenantId(), scope.clientId(),
                        scope.ownerJiacn(), targetAgentId));
        requireNativeExecution(nativeExecution);
        ServerLane lane = serverLane();
        List<String> requested = lane.state().equals("READY") && policy.eligible()
                && nativeExecution.state() == NativeBountyExecutionSessionLookup.State.READY
                ? intersection(SERVER_OPERATIONS, nativeExecution.supportedOperations()) : List.of();
        String initial = requested.size() == 1 ? requested.getFirst() : null;
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        blockers.addAll(lane.blockingReasons());
        if (!policy.eligible()) blockers.add(policy.blockingReason());
        if (nativeExecution.state() != NativeBountyExecutionSessionLookup.State.READY) {
            blockers.add("NATIVE_EXECUTION_" + nativeExecution.state().name());
        }
        if (requested.isEmpty() && nativeExecution.state() == NativeBountyExecutionSessionLookup.State.READY
                && lane.state().equals("READY") && policy.eligible()) {
            blockers.add("NO_ELIGIBLE_OPERATION");
        }
        blockers.add("COST_AUTHORIZATION_UNAVAILABLE");
        return new Capability(1, taskId, targetAgentId, LANE, lane,
                new NativeExecution(nativeExecution.state().name(), nativeExecution.transport(),
                        nativeExecution.schemaVersion(), nativeExecution.supportedOperations()),
                new Authorization("UNAVAILABLE", false),
                new NewStart(false, List.copyOf(blockers)), requested, initial, "EMPTY_ONLY",
                new OriginalIntentRecovery(false, "RECOVERY_REQUIRED",
                        "EXPLICIT_USER_EXACT_ORIGINAL_KEY_AND_BODY_ONLY"));
    }

    private ServerLane serverLane() {
        List<String> disabled = new ArrayList<>();
        if (!flags.storageEnabled()) disabled.add("PERSONAL_WORKSPACE_STORAGE_DISABLED");
        if (!flags.conversationEnabled()) disabled.add("CONVERSATION_EXECUTION_DISABLED");
        if (!flags.websocketEnabled()) disabled.add("AGENT_WEBSOCKET_DISABLED");
        if (!flags.bootstrapEnabled()) disabled.add("BOUNTY_BOOTSTRAP_DISABLED");
        if (!flags.executionEnabled()) disabled.add("BOUNTY_EXECUTION_DISABLED");
        if (!flags.operationReadEnabled()) disabled.add("ASSIGNMENT_OPERATION_READ_DISABLED");
        if (!flags.requirementReadEnabled()) disabled.add("CURRENT_REQUIREMENT_READ_DISABLED");
        if (!disabled.isEmpty()) return new ServerLane("DISABLED", List.copyOf(disabled));
        List<String> notRunning = new ArrayList<>();
        if (storage.maxContentBytes() < 1) notRunning.add("PERSONAL_WORKSPACE_STORAGE_NOT_READY");
        ChatBountyBootstrapRelay bootstrap = bootstrapRelay.getIfAvailable();
        if (bootstrap == null || !bootstrap.isRunning()) notRunning.add("BOUNTY_BOOTSTRAP_NOT_RUNNING");
        ChatBountyExecutionRelay execution = executionRelay.getIfAvailable();
        if (execution == null || !execution.isRunning()) notRunning.add("BOUNTY_EXECUTION_NOT_RUNNING");
        return notRunning.isEmpty() ? new ServerLane("READY", List.of())
                : new ServerLane("NOT_RUNNING", List.copyOf(notRunning));
    }


    private static void requireNativeExecution(NativeBountyExecutionSessionLookup.Snapshot snapshot) {
        if (snapshot == null || snapshot.state() == null) throw new SourceUnavailable();
        boolean ready = snapshot.state() == NativeBountyExecutionSessionLookup.State.READY;
        if (ready && (!Objects.equals(1, snapshot.schemaVersion())
                || !Objects.equals("PERSONAL_WORKSPACE_CONVERSATION_HTTP_V1", snapshot.transport())
                || !List.of("GENERATE_IMAGE").equals(snapshot.supportedOperations()))
                || !ready && (snapshot.schemaVersion() != null || snapshot.transport() != null
                        || snapshot.supportedOperations() == null
                        || !snapshot.supportedOperations().isEmpty())) {
            throw new SourceUnavailable();
        }
    }

    private static List<String> intersection(List<String> server, List<String> declared) {
        if (declared == null) throw new SourceUnavailable();
        return server.stream().filter(declared::contains).toList();
    }

    private static void requirePolicy(AgentTaskPointAndStartPolicyService.Snapshot policy,
            String taskId, String targetAgentId) {
        if (policy == null || !Objects.equals(taskId, policy.taskId())
                || !Objects.equals(targetAgentId, policy.targetAgentId())
                || policy.taskState() == null || policy.taskState().isBlank()
                || policy.eligible() && policy.blockingReason() != null
                || !policy.eligible() && (policy.blockingReason() == null
                        || policy.blockingReason().isBlank())) throw new SourceUnavailable();
    }

    public record Capability(int schemaVersion, String taskId, String targetAgentId, String lane,
            ServerLane serverLane, NativeExecution nativeExecution, Authorization authorization,
            NewStart newStart, List<String> requestedOperations, String initialOperation,
            String inputRefsPolicy, OriginalIntentRecovery originalIntentRecovery) {
        public Capability { requestedOperations = List.copyOf(requestedOperations); }
    }
    public record ServerLane(String state, List<String> blockingReasons) {
        public ServerLane { blockingReasons = List.copyOf(blockingReasons); }
    }
    public record NativeExecution(String state, String transport, Integer schemaVersion,
            List<String> supportedOperations) {
        public NativeExecution { supportedOperations = List.copyOf(supportedOperations); }
    }
    public record Authorization(String state, boolean paidExecutionAuthorized) { }
    public record NewStart(boolean eligible, List<String> blockingReasons) {
        public NewStart { blockingReasons = List.copyOf(blockingReasons); }
    }
    public record OriginalIntentRecovery(boolean legacyFallbackAllowed,
            String unknownOrNotFoundMeans, String replayPolicy) { }
    record Flags(boolean storageEnabled, boolean conversationEnabled, boolean websocketEnabled,
            boolean bootstrapEnabled, boolean executionEnabled, boolean operationReadEnabled,
            boolean requirementReadEnabled) { }
    public static final class SourceUnavailable extends RuntimeException { }
}
