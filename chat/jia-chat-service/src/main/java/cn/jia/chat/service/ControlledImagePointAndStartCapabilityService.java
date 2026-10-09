package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.agent.service.ControlledImageProviderAuthorityLookup;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Side-effect-free controlled-image v3 capability; consent remains explicitly required. */
@Service
public final class ControlledImagePointAndStartCapabilityService {
    private static final String LANE="ORDINARY_SINGLE_AGENT_CONTROLLED_IMAGE_ASSIGN_AND_START";
    private static final String TRANSPORT="PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V3";
    private static final List<String> OPERATIONS=List.of("GENERATE_IMAGE");
    private final ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> declarations;
    private final ControlledImageProviderAuthorityLookup operatorAuthority;
    private final AgentTaskPointAndStartPolicyService taskPolicy;
    private final PersonalWorkspaceStorage storage;
    private final ObjectProvider<ChatBountyBootstrapRelay> bootstrapRelay;
    private final ObjectProvider<ChatBountyExecutionRelay> executionRelay;
    private final Flags flags;

    @Autowired
    public ControlledImagePointAndStartCapabilityService(
            ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> declarations,
            ControlledImageProviderAuthorityLookup operatorAuthority,
            AgentTaskPointAndStartPolicyService taskPolicy,PersonalWorkspaceStorage storage,
            ObjectProvider<ChatBountyBootstrapRelay> bootstrapRelay,
            ObjectProvider<ChatBountyExecutionRelay> executionRelay,
            @Value("${agent.controlled-image-provider.bridge-enabled:false}") boolean bridgeEnabled,
            @Value("${agent.controlled-image-provider.enabled:false}") boolean providerEnabled,
            @Value("${agent.personal-workspace-storage.enabled:false}") boolean storageEnabled,
            @Value("${jia.agent.conversation-execution.enabled:false}") boolean conversationEnabled,
            @Value("${jia.chat.service.websocket.enable:false}") boolean websocketEnabled,
            @Value("${chat.bounty-bootstrap.enabled:false}") boolean bootstrapEnabled,
            @Value("${chat.bounty-execution.enabled:false}") boolean executionEnabled,
            @Value("${agent.task-deliberation-operation.read-enabled:false}") boolean operationReadEnabled,
            @Value("${agent.task-requirement-snapshot.read-enabled:false}") boolean requirementReadEnabled,
            @Value("${agent.task-reference-inputs.enabled:false}") boolean referenceInputsEnabled) {
        this(declarations,operatorAuthority,taskPolicy,storage,bootstrapRelay,executionRelay,
                new Flags(bridgeEnabled,providerEnabled,storageEnabled,conversationEnabled,
                        websocketEnabled,bootstrapEnabled,executionEnabled,operationReadEnabled,
                        requirementReadEnabled,referenceInputsEnabled));
    }

    ControlledImagePointAndStartCapabilityService(
            ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> declarations,
            ControlledImageProviderAuthorityLookup operatorAuthority,
            AgentTaskPointAndStartPolicyService taskPolicy,PersonalWorkspaceStorage storage,
            ObjectProvider<ChatBountyBootstrapRelay> bootstrapRelay,
            ObjectProvider<ChatBountyExecutionRelay> executionRelay,Flags flags) {
        this.declarations=Objects.requireNonNull(declarations);this.operatorAuthority=Objects.requireNonNull(operatorAuthority);
        this.taskPolicy=Objects.requireNonNull(taskPolicy);this.storage=Objects.requireNonNull(storage);
        this.bootstrapRelay=Objects.requireNonNull(bootstrapRelay);this.executionRelay=Objects.requireNonNull(executionRelay);
        this.flags=Objects.requireNonNull(flags);
    }

    public Capability read(AgentTaskExecutionGrantService.Scope scope,String taskId,String targetAgentId) {
        var policy=taskPolicy.read(scope,taskId,targetAgentId);
        requirePolicy(policy,taskId,targetAgentId);
        ServerLane lane=serverLane();
        var lookup=declarations.getIfUnique();
        var session=lookup==null?null:lookup.current(
                new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.DeclarationScope(
                        scope.tenantId(),scope.clientId(),scope.ownerJiacn(),targetAgentId));
        requireSession(session);
        ControlledImageProviderAuthorityLookup.Snapshot operator=null;
        if(session.state()==ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY) {
            operator=operatorAuthority.current(new ControlledImageProviderAuthorityLookup.Scope(
                    scope.tenantId(),scope.clientId(),scope.ownerJiacn()),targetAgentId,
                    session.bindingId(),Objects.requireNonNull(session.bindingEpoch()));
            requireOperator(operator,session);
        }
        LinkedHashSet<String> blockers=new LinkedHashSet<>(lane.blockingReasons());
        if(!policy.eligible())blockers.add(policy.blockingReason());
        if(session.state()!=ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY)
            blockers.add("CONTROLLED_EXECUTION_"+session.state().name());
        if(operator==null||operator.state()!=ControlledImageProviderAuthorityLookup.State.READY)
            blockers.add("OPERATOR_POLICY_UNAVAILABLE");
        boolean ready="READY".equals(lane.state())&&policy.eligible()
                &&session.state()==ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY
                &&operator!=null&&operator.state()==ControlledImageProviderAuthorityLookup.State.READY;
        if(ready)blockers.add("OWNER_EXACT_CONSENT_REQUIRED");
        List<String> requested=ready?OPERATIONS:List.of();
        return new Capability(3,taskId,targetAgentId,LANE,lane,
                ready?new ControlledExecution("READY",TRANSPORT,3,OPERATIONS)
                        :new ControlledExecution("UNAVAILABLE",null,null,List.of()),
                ready?new ProviderBinding(session.providerLane(),session.bindingId(),
                        Long.toString(session.bindingEpoch()),session.modelId(),16,1,1):null,
                new ExecutionAuthorization(ready?"CONSENT_REQUIRED":"UNAVAILABLE",false),
                new NewStart(false,List.copyOf(blockers)),requested,
                requested.size()==1?requested.getFirst():null,"TASK_LINKED_REFERENCE",16,
                new OriginalIntentRecovery(false,"RECOVERY_REQUIRED",
                        "EXPLICIT_USER_EXACT_ORIGINAL_KEY_AND_BODY_ONLY"));
    }

    private ServerLane serverLane() {
        List<String> disabled=new ArrayList<>();
        if(!flags.bridgeEnabled())disabled.add("CONTROLLED_IMAGE_BRIDGE_DISABLED");
        if(!flags.providerEnabled())disabled.add("CONTROLLED_IMAGE_PROVIDER_DISABLED");
        if(!flags.storageEnabled())disabled.add("PERSONAL_WORKSPACE_STORAGE_DISABLED");
        if(!flags.conversationEnabled())disabled.add("CONVERSATION_EXECUTION_DISABLED");
        if(!flags.websocketEnabled())disabled.add("AGENT_WEBSOCKET_DISABLED");
        if(!flags.bootstrapEnabled())disabled.add("BOUNTY_BOOTSTRAP_DISABLED");
        if(!flags.executionEnabled())disabled.add("BOUNTY_EXECUTION_DISABLED");
        if(!flags.operationReadEnabled())disabled.add("ASSIGNMENT_OPERATION_READ_DISABLED");
        if(!flags.requirementReadEnabled())disabled.add("CURRENT_REQUIREMENT_READ_DISABLED");
        if(!flags.referenceInputsEnabled())disabled.add("TASK_REFERENCE_INPUTS_DISABLED");
        if(!disabled.isEmpty())return new ServerLane("DISABLED",List.copyOf(disabled));
        List<String> notRunning=new ArrayList<>();
        if(storage.maxContentBytes()<1)notRunning.add("PERSONAL_WORKSPACE_STORAGE_NOT_READY");
        var bootstrap=bootstrapRelay.getIfAvailable();
        if(bootstrap==null||!bootstrap.isRunning())notRunning.add("BOUNTY_BOOTSTRAP_NOT_RUNNING");
        var execution=executionRelay.getIfAvailable();
        if(execution==null||!execution.isRunning())notRunning.add("BOUNTY_EXECUTION_NOT_RUNNING");
        return notRunning.isEmpty()?new ServerLane("READY",List.of())
                :new ServerLane("NOT_RUNNING",List.copyOf(notRunning));
    }

    private static void requireSession(
            ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration value) {
        if(value==null||value.state()==null)throw new SourceUnavailable();
        boolean ready=value.state()==ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY;
        if(ready&&(value.runtimeInstanceId()==null
                ||value.operations()==null||!Set.copyOf(value.operations()).equals(Set.of("GENERATE_IMAGE","EDIT_IMAGE"))
                ||!"CONTROLLED_IMAGE_HTTP_V1".equals(value.providerLane())
                ||value.bindingId()==null||value.bindingEpoch()==null||value.bindingEpoch()<1
                ||value.modelId()==null||!Objects.equals(value.maxInputItems(),16)
                ||!Objects.equals(value.maxOutboundRequestAttempts(),1)
                ||!Objects.equals(value.precallFenceVersion(),1))
                ||!ready&&(value.runtimeInstanceId()!=null||value.providerLane()!=null
                    ||value.bindingId()!=null||value.bindingEpoch()!=null||value.modelId()!=null
                    ||value.operations()==null||!value.operations().isEmpty()))
            throw new SourceUnavailable();
    }
    private static void requireOperator(ControlledImageProviderAuthorityLookup.Snapshot value,
            ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration session) {
        if(value==null||value.state()==null)throw new SourceUnavailable();
        if(value.state()==ControlledImageProviderAuthorityLookup.State.READY
                &&(!Objects.equals(value.providerLane(),session.providerLane())
                    ||!Objects.equals(value.bindingId(),session.bindingId())
                    ||!Objects.equals(value.bindingEpoch(),Long.toString(session.bindingEpoch()))
                    ||!Objects.equals(value.modelId(),session.modelId())
                    ||!Objects.equals(value.maxOutboundRequestAttempts(),1)))throw new SourceUnavailable();
    }
    private static void requirePolicy(AgentTaskPointAndStartPolicyService.Snapshot value,
            String taskId,String targetAgentId) {
        if(value==null||!Objects.equals(taskId,value.taskId())||!Objects.equals(targetAgentId,value.targetAgentId())
                ||value.taskState()==null||value.taskState().isBlank()
                ||value.eligible()&&value.blockingReason()!=null
                ||!value.eligible()&&(value.blockingReason()==null||value.blockingReason().isBlank()))
            throw new SourceUnavailable();
    }

    public record Capability(int schemaVersion,String taskId,String targetAgentId,String lane,
            ServerLane serverLane,ControlledExecution controlledExecution,ProviderBinding providerBinding,
            ExecutionAuthorization executionAuthorization,NewStart newStart,List<String> requestedOperations,
            String initialOperation,String inputRefsPolicy,int maxInputItems,
            OriginalIntentRecovery originalIntentRecovery) {
        public Capability { requestedOperations=List.copyOf(requestedOperations); }
    }
    public record ServerLane(String state,List<String> blockingReasons) {
        public ServerLane { blockingReasons=List.copyOf(blockingReasons); }
    }
    public record ControlledExecution(String state,String transport,Integer schemaVersion,
            List<String> supportedOperations) {
        public ControlledExecution { supportedOperations=List.copyOf(supportedOperations); }
    }
    public record ProviderBinding(String providerLane,String bindingId,String bindingEpoch,
            String modelId,int maxInputItems,int maxOutboundRequestAttempts,int precallFenceVersion) { }
    public record ExecutionAuthorization(String state,boolean paidExecutionAuthorized) { }
    public record NewStart(boolean eligible,List<String> blockingReasons) {
        public NewStart { blockingReasons=List.copyOf(blockingReasons); }
    }
    public record OriginalIntentRecovery(boolean legacyFallbackAllowed,String unknownOrNotFoundMeans,
            String replayPolicy) { }
    record Flags(boolean bridgeEnabled,boolean providerEnabled,boolean storageEnabled,
            boolean conversationEnabled,boolean websocketEnabled,boolean bootstrapEnabled,
            boolean executionEnabled,boolean operationReadEnabled,boolean requirementReadEnabled,
            boolean referenceInputsEnabled) { }
    public static final class SourceUnavailable extends RuntimeException { }
}
