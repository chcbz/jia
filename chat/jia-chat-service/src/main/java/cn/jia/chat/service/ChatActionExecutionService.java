package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.agent.service.TaskSkillPolicyService;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Agent actions enter the existing execution/result pipeline in the durable consumer transaction.
 * Does not invoke a provider, manufacture a USER/approval, or grant a new account policy. */
@Service
public class ChatActionExecutionService implements ChatActionDispatchService.Executor {
    private final ChatDeliberationService deliberation;
    private final ChatDeliberationDao dao;
    private final ChatTypedDeliberationStore typed;
    private final ChatInteractionStepStore steps;
    private final AgentTaskExecutionGrantService grants;
    private final SkillExecutionService executions;
    private final InstalledSkillDescriptorLookup installed;
    private final SkillCapabilityLookup runtimes;
    private final TaskSkillPolicyService policies;
    public ChatActionExecutionService(ChatDeliberationService deliberation, ChatDeliberationDao dao,
            ChatTypedDeliberationStore typed, ChatInteractionStepStore steps, AgentTaskExecutionGrantService grants,
            SkillExecutionService executions, InstalledSkillDescriptorLookup installed,
            SkillCapabilityLookup runtimes, TaskSkillPolicyService policies) {
        this.deliberation=deliberation; this.dao=dao; this.typed=typed; this.steps=steps;
        this.grants=grants; this.executions=executions; this.installed=installed;this.runtimes=runtimes;this.policies=policies;
    }

    private ChatActionCapabilityService nativeCapabilities;
    private cn.jia.chat.handler.TypedDeliberationSessionRegistry nativeSessions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setNativeCapabilities(ChatActionCapabilityService value,cn.jia.chat.handler.TypedDeliberationSessionRegistry sessions) {
        nativeCapabilities=value;nativeSessions=sessions;
    }
    private static ChatActionOutcomeContract.Capability selectedCapability(ChatActionFinalService.BoundAction action) {
        return action.validated().dispatchFacts().availableActions().stream()
                .filter(c->c.actionId().equals(action.validated().interactionOutcome().action().actionId())).findFirst().orElseThrow(ChatActionExecutionService::conflict);
    }
    private void requireCurrentNative(ChatActionFinalService.BoundAction action) {
        if(nativeCapabilities==null||nativeSessions==null)throw new ChatActionDispatchService.Unavailable("NATIVE_RUNTIME_UNAVAILABLE");
        var scope=action.outcome().scope();var target=(String)action.validated().binding().get("targetAgentId");
        var key=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),target);
        var policy=policies.read(key,action.outcome().taskId());requireAvailablePolicy(policy);requireRuntimeTarget(key,policy.runtimeScope());
        final cn.jia.chat.handler.TypedDeliberationSessionRegistry.Ready ready;
        try {ready=nativeSessions.requireSingleReady(new cn.jia.chat.handler.TypedDeliberationSessionRegistry.Scope(scope.tenantId(),policy.runtimeScope().ownerJiacn(),scope.clientId()),target);}
        catch(IllegalStateException unavailable){throw new ChatActionDispatchService.Unavailable("NATIVE_RUNTIME_UNAVAILABLE");}
        var current=nativeCapabilities.availableNative(new ChatActionCapabilityService.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),target),action.outcome().taskId(),
                ChatTypedDeliberationContextService.parseCatalog(action.outcome().sourceCatalogJson()),ready.frozenDeclaration(),ready.sessionId());
        var original=selectedCapability(action);
        if(current.stream().noneMatch(c->original.actionId().equals(c.get("actionId"))))throw new ChatActionDispatchService.Rejected();
    }

    @Override
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void prepare(ChatActionFinalService.BoundAction action) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new InstalledSkillDescriptorLookup.SourceUnavailable();
        if("EXECUTE_NATIVE".equals(selectedCapability(action).operation())) {requireCurrentNative(action);return;}
        var scope=action.outcome().scope();
        var taskScope=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                (String)action.validated().binding().get("targetAgentId"));
        var policy=policies.read(taskScope,action.outcome().taskId());
        requireAvailablePolicy(policy);
        requireRuntimeTarget(taskScope,policy.runtimeScope());
        installed.read(policy.runtimeScope());
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public String admit(ChatActionFinalService.BoundAction action) {
        var parent=action.outcome(); var scope=parent.scope();
        var requested=action.validated().interactionOutcome().action();
        var capability=selectedCapability(action);
        if (!"EXECUTE".equals(capability.kind()) || !java.util.Set.of("EXECUTE_SKILL","EXECUTE_NATIVE").contains(capability.operation())) throw conflict();
        String target=(String)action.validated().binding().get("targetAgentId");
        String actionId=ChatActionFinalValidator.actionEventId(action.validated());
        String requestId=stable("action-execute",actionId), stepId=stable("action-step",actionId);
        String intentId=stable("action-execution",actionId);
        var user=deliberation.requireActionUser(action);
        String userHash=ChatBountyInteractionV3Wire.shaText(user.getContent());
        var catalog=selectedCatalog(action);
        var body=new LinkedHashMap<String,Object>();
        body.put("schemaVersion",4); body.put("origin","AGENT_ACTION"); body.put("actionRequestId",actionId);
        body.put("parentOutcomeId",parent.outcomeId()); body.put("parentFinalDigest",parent.finalDigest());
        body.put("originalUserMessageId",Long.toString(user.getId())); body.put("originalUserContentSha256",userHash);
        body.put("operation",capability.operation()); body.put("skill",ChatActionFinalValidator.skillMap(capability.skill())); body.put("parameters",requested.parameters()); body.put("instruction",requested.instruction()); body.put("sources",catalog);
        String bodyDigest=ChatDeliberationService.digest(body);
        var prior=typed.findAdmissionByKey(scope,actionId,true);
        if (prior!=null) {
            requireReplay(action,prior,requestId,stepId,intentId,bodyDigest,CanonicalContextJson.write(catalog));
            return requestId; // No live source/runtime/policy lookup, reserve, dispatch or Provider call.
        }
        if("EXECUTE_NATIVE".equals(capability.operation()))requireCurrentNative(action);
        if (dao.findRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId)!=null) throw conflict();
        // Existing action keys returned above, before any current policy/descriptor/source lookup.
        var grantScope=new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        var baseline=grants.resolveAndAdmit(grantScope,parent.taskId(),parent.assignmentRevision(),target,"EXECUTE_SKILL",false);
        var snapshot="EXECUTE_NATIVE".equals(capability.operation())?prepareNative(action,actionId,capability,catalog,baseline):prepare(action,actionId,capability,catalog,baseline);
        var creation=executions.create(new PersonalWorkspaceExecutionService.OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                new SkillExecutionService.Create(actionId,parent.taskId(),scope.conversationId(),scope.conversationGeneration(),target,
                        baseline.grantId(),baseline.grantVersion(),parent.assignmentRevision(),snapshot));
        if(creation==null||!parent.taskId().equals(creation.taskId())||!scope.conversationId().equals(creation.conversationId())
                ||!target.equals(creation.targetAgentId())||!"QUEUED".equals(creation.state())
                ||!ChatSkillSnapshotJson.sha(snapshot).equals(creation.snapshotDigest()))throw conflict();
        String executionId=creation.executionId();
        long now=System.currentTimeMillis();
        var child=new ChatRequestEntity().setTenantId(scope.tenantId()).setOwnerJiacn(scope.ownerJiacn())
                .setClientId(scope.clientId()).setRequestId(requestId).setRequestRevision(1L).setRequestDigest(bodyDigest)
                .setConversationId(scope.conversationId()).setConversationGeneration(scope.conversationGeneration())
                .setUserMessageId(user.getId()).setAggregateState("RUNNING").setStateVersion(0L).setCreatedAt(now).setUpdatedAt(now);
        if (dao.insertRequest(child)!=1 || child.getId()==null) throw persistence();
        var step=new ChatInteractionStepStore.Step(stepId,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId,1,1,
                scope.conversationId(),scope.conversationGeneration(),parent.taskId(),parent.assignmentRevision(),
                baseline.grantId(),baseline.grantVersion(),target,"EXECUTE","RUNNING",0,
                (String)snapshot.get("sourceSnapshotDigest"),now,now);
        if (steps.insertStep(step)!=1 || steps.insertLink(new ChatInteractionStepStore.ExecutionLink(intentId,scope.tenantId(),
                scope.ownerJiacn(),scope.clientId(),stepId,executionId,"RUNNING",0,now,now))!=1) throw persistence();
        var admission=new ChatTypedDeliberationStore.Admission("action-admit_"+actionId.substring(4),scope,actionId,
                bodyDigest,bodyDigest,"DISCUSSION",parent.taskId(),parent.assignmentRevision(),parent.outcomeId(),null,
                requestId,1,user.getId(),"[]",CanonicalContextJson.write(catalog),"ADMITTED",0,
                dao.eventHighWatermark(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),scope.conversationId(),scope.conversationGeneration()),now);
        if (typed.insertAdmission(admission)!=1) throw persistence();
        deliberation.persistActionStarted(action,requestId,"EXECUTE",now);
        return requestId;
    }

    private Map<String,Object> prepareNative(ChatActionFinalService.BoundAction action,String actionId,
            ChatActionOutcomeContract.Capability capability,List<Map<String,Object>> catalog,AgentTaskExecutionGrantService.Admission baseline) {
        var parent=action.outcome();var scope=parent.scope();var inputs=new ArrayList<Map<String,Object>>();
        for(var source:catalog) {
            var input=new LinkedHashMap<String,Object>();String kind=(String)source.get("sourceKind");
            if("TASK_WORKSPACE_FILE".equals(kind))kind="TASK_LINKED_WORKSPACE_VERSION";
            if(!List.of("TASK_LINKED_WORKSPACE_VERSION","CURRENT_CONVERSATION_ASSET","EXECUTION_RESULT","FORMAL_ARTIFACT").contains(kind))throw conflict();
            input.put("sourceKind",kind);input.put("sourceIdentity",source.get("sourceIdentity"));input.put("inputRef",source.get("sourceRefId"));
            input.put("contentMimeType",source.get("contentMimeType"));input.put("byteLength",source.get("byteLength"));input.put("sha256",source.get("contentHash"));inputs.add(input);
        }
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("schemaVersion",1);snapshot.put("executionProtocolVersion",5);
        snapshot.put("toolPolicyRef",capability.skill()==null?SkillExecutionService.NATIVE_LOCAL_FILES_POLICY:SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY);snapshot.put("actionEventId",actionId);snapshot.put("taskId",parent.taskId());
        snapshot.put("conversationId",scope.conversationId());snapshot.put("conversationGeneration",Long.toString(scope.conversationGeneration()));
        snapshot.put("targetAgentId",action.validated().binding().get("targetAgentId"));
        snapshot.put("taskGrant",Map.of("id",baseline.grantId(),"version",Long.toString(baseline.grantVersion()),"assignmentRevision",Long.toString(baseline.assignmentRevision())));
        snapshot.put("instruction",action.validated().interactionOutcome().action().instruction());snapshot.put("parameters",action.validated().interactionOutcome().action().parameters());
        snapshot.put("inputs",List.copyOf(inputs));snapshot.put("sourceSnapshotDigest",ChatSkillSnapshotJson.sha(inputs));
        snapshot.put("outputs",Map.of("mimeTypes",capability.outputMimeTypes(),"multiple",true,"artifactsRequired",true));
        if(capability.skill()==null)snapshot.put("skills",List.of());
        else {
            String target=(String)action.validated().binding().get("targetAgentId");
            var key=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),target);
            var policy=policies.read(key,parent.taskId());requireAvailablePolicy(policy);requireRuntimeTarget(key,policy.runtimeScope());
            snapshot.put("skills",List.of(nativeSelection(installed.readCurrent(policy.runtimeScope()),capability)));
        }
        return snapshot;
    }

    static Map<String,Object> nativeSelection(InstalledSkillDescriptorLookup.Snapshot current,ChatActionOutcomeContract.Capability capability) {
        var selected=capability.skill();
        var matches=current.skills().stream().filter(d->d.verifiedNative()&&d.manifestJson()==null&&d.manifestSha256()==null
                &&Objects.equals(d.skillKey(),selected.skillKey())&&Objects.equals(d.skillVersion(),selected.skillVersion())
                &&Objects.equals(d.packageSha256(),selected.packageSha256())&&Objects.equals(d.manifestSha256(),selected.manifestSha256())
                &&ChatActionCapabilityService.nativeSkillActionId(d,capability.inputMimeTypes(),capability.outputMimeTypes()).equals(capability.actionId())).toList();
        if(matches.size()!=1)throw new ChatActionDispatchService.Rejected();var descriptor=matches.getFirst();
        var skill=new LinkedHashMap<String,Object>();skill.put("key",descriptor.skillKey());skill.put("version",descriptor.skillVersion());
        skill.put("installationId",descriptor.installationId());skill.put("packageSha256",descriptor.packageSha256());skill.put("manifestSha256",descriptor.manifestSha256());
        var selection=new LinkedHashMap<String,Object>();selection.put("skill",skill);selection.put("providerBinding",null);return selection;
    }

    /** Frozen model selection is a locator, not authority. Core rechecks the exact installation and all inputs. */
    private Map<String,Object> prepare(ChatActionFinalService.BoundAction action,String actionId,
            ChatActionOutcomeContract.Capability capability,List<Map<String,Object>> catalog,
            AgentTaskExecutionGrantService.Admission baseline) {
        var parent=action.outcome();var scope=parent.scope();String target=(String)action.validated().binding().get("targetAgentId");
        var key=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),target);
        var policy=policies.read(key,parent.taskId());
        requireAvailablePolicy(policy);
        if(policy.fence()==null||policy.assignmentRevision()!=parent.assignmentRevision())throw new ChatActionDispatchService.Rejected();
        requireRuntimeTarget(key,policy.runtimeScope());
        var runtimeKey=policy.runtimeScope();
        var selected=capability.skill();
        var descriptors=installed.readCurrent(runtimeKey).skills().stream().filter(d->selected.skillKey().equals(d.skillKey())
                &&selected.skillVersion().equals(d.skillVersion())).toList();
        if(descriptors.size()!=1)throw new ChatActionDispatchService.Rejected();var descriptor=descriptors.getFirst();
        if(!descriptor.verified()||!selected.packageSha256().equals(descriptor.packageSha256())
                ||!selected.manifestSha256().equals(descriptor.manifestSha256()))throw new ChatActionDispatchService.Rejected();
        var manifest=ChatSkillSnapshotJson.object(descriptor.manifestJson());
        var runtime=runtimes.lookup(runtimeKey,policy.fence());
        if(runtime==null||runtime.state()==SkillCapabilityLookup.State.DISCONNECTED||runtime.state()==SkillCapabilityLookup.State.FENCED)
            throw new ChatActionDispatchService.Unavailable("SKILL_RUNTIME_UNAVAILABLE");
        if(runtime.state()!=SkillCapabilityLookup.State.AVAILABLE)throw new ChatActionDispatchService.Rejected();
        var reports=runtime.catalog().skills().stream().filter(d->selected.skillKey().equals(d.skillKey())&&selected.skillVersion().equals(d.skillVersion())).toList();
        if(reports.size()!=1)throw new ChatActionDispatchService.Rejected();var report=reports.getFirst();
        if(report.readiness()!=SkillCapabilityLookup.Readiness.READY||!selected.packageSha256().equals(report.packageSha256())
                ||!selected.manifestSha256().equals(report.manifestSha256()))throw new ChatActionDispatchService.Rejected();
        var currentPolicy=policies.read(key,parent.taskId());
        requireAvailablePolicy(currentPolicy);
        if(currentPolicy.assignmentRevision()!=parent.assignmentRevision())throw new ChatActionDispatchService.Rejected();
        // A connection may rotate while preparing. Roll back and re-read trusted facts on retry;
        // never admit using the old fence or reinterpret a transport race as revoked authority.
        if(!policy.equals(currentPolicy)||!runtime.equals(runtimes.lookup(runtimeKey,policy.fence())))
            throw new ChatActionDispatchService.Unavailable("SKILL_RUNTIME_CHANGED");
        var inputs=new ArrayList<Map<String,Object>>();
        for(var source:catalog) {
            var input=new LinkedHashMap<String,Object>();
            String kind=(String)source.get("sourceKind");if("TASK_WORKSPACE_FILE".equals(kind))kind="TASK_LINKED_WORKSPACE_VERSION";
            if(!List.of("TASK_LINKED_WORKSPACE_VERSION","CURRENT_CONVERSATION_ASSET","EXECUTION_RESULT","FORMAL_ARTIFACT").contains(kind))throw conflict();
            input.put("sourceKind",kind);input.put("sourceIdentity",source.get("sourceIdentity"));
            input.put("inputRef",source.get("sourceRefId"));input.put("contentMimeType",source.get("contentMimeType"));
            input.put("byteLength",source.get("byteLength"));input.put("sha256",source.get("contentHash"));inputs.add(input);
        }
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("schemaVersion",1);snapshot.put("executionProtocolVersion",5);snapshot.put("toolPolicyRef",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY);snapshot.put("actionEventId",actionId);
        snapshot.put("taskId",parent.taskId());snapshot.put("conversationId",scope.conversationId());
        snapshot.put("conversationGeneration",Long.toString(scope.conversationGeneration()));snapshot.put("targetAgentId",target);
        snapshot.put("taskGrant",Map.of("id",baseline.grantId(),"version",Long.toString(baseline.grantVersion()),
                "assignmentRevision",Long.toString(baseline.assignmentRevision())));
        var selectedSkill=Map.of("key",descriptor.skillKey(),"version",descriptor.skillVersion(),"installationId",descriptor.installationId(),
                "packageSha256",descriptor.packageSha256(),"manifestSha256",descriptor.manifestSha256(),"runner",manifest.get("runner"),"entrypoint",manifest.get("entrypoint"));
        snapshot.put("instruction",action.validated().interactionOutcome().action().instruction());
        snapshot.put("parameters",action.validated().interactionOutcome().action().parameters());snapshot.put("inputs",List.copyOf(inputs));
        snapshot.put("outputs",manifest.get("outputs"));snapshot.put("sourceSnapshotDigest",ChatSkillSnapshotJson.sha(inputs));
        var requirements=(Map<?,?>)manifest.get("requirements");
        var selection=new LinkedHashMap<String,Object>();selection.put("skill",selectedSkill);
        selection.put("providerBinding",requirements.get("providerAlias")==null?null:Map.of("alias",requirements.get("providerAlias"),"revision",report.bindingRevision()));
        snapshot.put("skills",List.of(selection));
        snapshot.put("costInfo",manifest.getOrDefault("costInfo",Map.of("mode","UNKNOWN")));
        // No producer/replaces is inferred from an uploaded input. Output lineage comes only from committed results.
        return snapshot;
    }

    private static void requireAvailablePolicy(TaskSkillPolicyService.Snapshot policy) {
        if (policy == null) throw new ChatActionDispatchService.Rejected();
        switch (policy.decision()) {
            case ALLOWED -> {
                if (policy.runtimeScope() == null) throw new ChatActionDispatchService.Rejected();
            }
            case DENIED -> throw new ChatActionDispatchService.Rejected();
            case TEMPORARILY_UNAVAILABLE -> throw new ChatActionDispatchService.Unavailable("SKILL_RUNTIME_UNAVAILABLE");
            case INDETERMINATE -> throw new ChatActionDispatchService.Unavailable("SKILL_POLICY_INDETERMINATE");
        }
    }

    private static void requireRuntimeTarget(SkillCapabilityLookup.Scope task,SkillCapabilityLookup.Scope runtime) {
        if(!task.tenantId().equals(runtime.tenantId())||!task.clientId().equals(runtime.clientId())
                ||!task.agentId().equals(runtime.agentId()))throw new ChatActionDispatchService.Rejected();
    }

    private void requireReplay(ChatActionFinalService.BoundAction action,ChatTypedDeliberationStore.Admission prior,
            String requestId,String stepId,String intentId,String digest,String catalog) {
        var parent=action.outcome(); var scope=parent.scope();
        if (!scope.equals(prior.scope()) || !requestId.equals(prior.requestId()) || prior.requestRevision()!=1
                || !ChatActionFinalValidator.actionEventId(action.validated()).equals(prior.idempotencyKey())
                || !digest.equals(prior.bodyDigest()) || !digest.equals(prior.requestDigest())
                || !parent.outcomeId().equals(prior.parentOutcomeId()) || !parent.taskId().equals(prior.taskId())
                || prior.assignmentRevision()!=parent.assignmentRevision() || prior.userMessageId()!=action.admission().userMessageId()
                || !"ADMITTED".equals(prior.state()) || prior.stateVersion()!=0 || !"DISCUSSION".equals(prior.intent())
                || !"[]".equals(prior.turnIdsJson()) || !catalog.equals(prior.sourceCatalogJson())) throw conflict();
        var child=dao.findRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId);
        var step=steps.findStep(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId,1,1);
        var link=steps.findLink(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),stepId);
        if (child==null || !scope.tenantId().equals(child.getTenantId()) || !scope.ownerJiacn().equals(child.getOwnerJiacn())
                || !scope.clientId().equals(child.getClientId()) || !requestId.equals(child.getRequestId())
                || !Objects.equals(1L,child.getRequestRevision()) || !digest.equals(child.getRequestDigest())
                || !Objects.equals(prior.userMessageId(),child.getUserMessageId()) || !scope.conversationId().equals(child.getConversationId())
                || !Objects.equals(scope.conversationGeneration(),child.getConversationGeneration()) || step==null
                || !stepId.equals(step.stepId()) || !scope.tenantId().equals(step.tenantId()) || !scope.ownerJiacn().equals(step.ownerJiacn())
                || !scope.clientId().equals(step.clientId()) || !requestId.equals(step.requestId()) || step.requestRevision()!=1 || step.stepNumber()!=1
                || !scope.conversationId().equals(step.conversationId()) || scope.conversationGeneration()!=step.conversationGeneration()
                || !parent.taskId().equals(step.taskId()) || parent.assignmentRevision()!=step.assignmentRevision()
                || !action.validated().binding().get("targetAgentId").equals(step.targetAgentId()) || !"EXECUTE".equals(step.kind())
                || link==null || !stepId.equals(link.stepId()) || !intentId.equals(link.executionIntentId()) || link.executionId()==null
                || !scope.tenantId().equals(link.tenantId()) || !scope.ownerJiacn().equals(link.ownerJiacn()) || !scope.clientId().equals(link.clientId())) throw conflict();
    }

    private static List<Map<String,Object>> selectedCatalog(ChatActionFinalService.BoundAction action) {
        var catalog="CHAT".equals(action.validated().binding().get("route"))
                ? ChatTypedDeliberationContextService.parseCatalog(action.outcome().sourceCatalogJson())
                : ChatTypedInspectionContextService.sources(action.outcome().sourceCatalogJson());
        var selected=new ArrayList<Map<String,Object>>();
        for (String id:action.validated().interactionOutcome().action().sourceRefIds()) {
            var entry=catalog.stream().filter(s->id.equals(s.get("sourceRefId"))).findFirst().orElseThrow(ChatActionExecutionService::conflict);
            selected.add(entry);
        }
        return List.copyOf(selected);
    }

    private static String stable(String domain,String actionId) { return ChatBountyInteractionV3Wire.shaText(domain+"\n"+actionId); }
    private static ChatDeliberationException conflict() { return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Action execution changed"); }
    private static ChatDeliberationException persistence() { return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Unable to persist action execution"); }
}
