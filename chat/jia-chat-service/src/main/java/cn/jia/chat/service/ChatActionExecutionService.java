package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
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
    private final ChatConversationAssetSourceResolver sources;
    private final ControlledImageFollowupAuthorityService authority;

    public ChatActionExecutionService(ChatDeliberationService deliberation, ChatDeliberationDao dao,
            ChatTypedDeliberationStore typed, ChatInteractionStepStore steps, AgentTaskExecutionGrantService grants,
            ChatConversationAssetSourceResolver sources, ControlledImageFollowupAuthorityService authority) {
        this.deliberation=deliberation; this.dao=dao; this.typed=typed; this.steps=steps;
        this.grants=grants; this.sources=sources; this.authority=authority;
    }

    @Override
    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public String admit(ChatActionFinalService.BoundAction action) {
        var parent=action.outcome(); var scope=parent.scope();
        var requested=action.validated().interactionOutcome().action();
        var capability=action.validated().dispatchFacts().availableActions().stream()
                .filter(c->c.actionId().equals(requested.actionId())).findFirst().orElseThrow(ChatActionExecutionService::conflict);
        if (!"EXECUTE".equals(capability.kind()) || !List.of("GENERATE_IMAGE","EDIT_IMAGE").contains(capability.operation())) throw conflict();
        String target=(String)action.validated().binding().get("targetAgentId");
        String actionId=ChatActionFinalValidator.actionEventId(action.validated());
        String requestId=stable("action-execute",actionId), stepId=stable("action-step",actionId);
        String intentId=stable("action-execution",actionId), executionId="pwe_"+intentId;
        String runId="pwe_run_"+ChatBountyInteractionV3Wire.shaText("run\n"+intentId);
        var user=deliberation.requireActionUser(action);
        String userHash=ChatBountyInteractionV3Wire.shaText(user.getContent());
        var catalog=selectedCatalog(action);
        var body=new LinkedHashMap<String,Object>();
        body.put("schemaVersion",3); body.put("origin","AGENT_ACTION"); body.put("actionRequestId",actionId);
        body.put("parentOutcomeId",parent.outcomeId()); body.put("parentFinalDigest",parent.finalDigest());
        body.put("originalUserMessageId",Long.toString(user.getId())); body.put("originalUserContentSha256",userHash);
        body.put("operation",capability.operation()); body.put("instruction",requested.instruction()); body.put("sources",catalog);
        String bodyDigest=ChatDeliberationService.digest(body);
        var prior=typed.findAdmissionByKey(scope,actionId,true);
        if (prior!=null) {
            requireReplay(action,prior,requestId,stepId,intentId,executionId,bodyDigest,CanonicalContextJson.write(catalog));
            return requestId; // No live source/runtime/policy lookup, reserve, dispatch or Provider call.
        }
        if (dao.findRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId)!=null) throw conflict();
        var command=prepare(action,actionId,requestId,stepId,intentId,capability.operation(),bodyDigest,catalog);
        var reservation=authority.admitOrdinaryAction(new ControlledImageFollowupAuthorityService.Scope(
                        scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                new ControlledImageFollowupAuthorityService.OrdinaryActionCommand(command,actionId,parent.outcomeId(),
                        parent.finalDigest(),user.getId(),userHash,executionId,runId,bodyDigest.substring(7)),()->{
                    var current=deliberation.requireActionUser(action);
                    if (!Objects.equals(user.getId(),current.getId()) || !userHash.equals(ChatBountyInteractionV3Wire.shaText(current.getContent()))
                            || !command.equals(prepare(action,actionId,requestId,stepId,intentId,capability.operation(),bodyDigest,catalog))) throw conflict();
                });
        if (!executionId.equals(reservation.executionId()) || !runId.equals(reservation.runId())) throw conflict();
        long now=System.currentTimeMillis();
        var child=new ChatRequestEntity().setTenantId(scope.tenantId()).setOwnerJiacn(scope.ownerJiacn())
                .setClientId(scope.clientId()).setRequestId(requestId).setRequestRevision(1L).setRequestDigest(bodyDigest)
                .setConversationId(scope.conversationId()).setConversationGeneration(scope.conversationGeneration())
                .setUserMessageId(user.getId()).setAggregateState("RUNNING").setStateVersion(0L).setCreatedAt(now).setUpdatedAt(now);
        if (dao.insertRequest(child)!=1 || child.getId()==null) throw persistence();
        var step=new ChatInteractionStepStore.Step(stepId,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId,1,1,
                scope.conversationId(),scope.conversationGeneration(),parent.taskId(),parent.assignmentRevision(),
                command.baseline().grantId(),command.baseline().grantVersion(),target,"EXECUTE","RUNNING",0,
                command.sourceSnapshotSha256(),now,now);
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

    private ControlledImageFollowupAuthorityService.PreviewCommand prepare(ChatActionFinalService.BoundAction action,
            String actionId,String requestId,String stepId,String intentId,String operation,String bodyDigest,
            List<Map<String,Object>> catalog) {
        var parent=action.outcome(); var scope=parent.scope();
        String target=(String)action.validated().binding().get("targetAgentId");
        var grantScope=new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        var current=grants.currentFollowupContext(grantScope,parent.taskId(),target);
        if (current==null || !parent.taskId().equals(current.taskId()) || !target.equals(current.targetAgentId())
                || current.assignmentRevision()!=parent.assignmentRevision()) throw new ChatActionDispatchService.Rejected();
        var baseline=grants.resolveFollowupBaseline(grantScope,parent.taskId(),current.baselineGrantVersion(),current.taskVersion(),
                current.assignmentRevision(),current.requirementRevision(),target);
        var refs=catalog.stream().map(ChatActionExecutionService::selector).map(s->new ChatConversationAssetSourceResolver.Ref(
                s.kind(),s.fileId(),s.version()==null?null:Integer.valueOf(s.version()),s.purpose(),s.assetId(),
                s.assetRevision()==null?null:Long.valueOf(s.assetRevision()))).toList();
        List<ControlledImageFollowupAuthorityService.Source> resolved;
        try {
            resolved=sources.resolveAction(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.conversationId(),
                    scope.conversationGeneration(),parent.taskId(),target,operation,refs,baseline.inputs());
        } catch (ChatDeliberationException stale) {
            if (stale.reason()==ChatDeliberationException.Reason.CONFLICT
                    || stale.reason()==ChatDeliberationException.Reason.INVALID_REQUEST
                    || stale.reason()==ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN)
                throw new ChatActionDispatchService.Rejected();
            throw stale;
        }
        if (resolved.size()!=catalog.size()) throw new ChatActionDispatchService.Rejected();
        // The adapter re-resolves ACL/lineage; compare bytes with the immutable dispatch catalogue too.
        for (int i=0;i<resolved.size();i++) {
            var expected=catalog.get(i); var actual=resolved.get(i);
            boolean chat="CHAT".equals(action.validated().binding().get("route"));
            if (!actual.contentMimeType().equals(expected.get(chat?"contentMimeType":"mimeType"))
                    || !actual.sha256().equals(expected.get(chat?"contentHash":"sha256"))
                    || !Long.toString(actual.byteLength()).equals(expected.get("byteLength"))) throw new ChatActionDispatchService.Rejected();
            var ref=refs.get(i);
            if (!ref.kind().equals(actual.kind()) || !Objects.equals(ref.fileId(),actual.fileId())
                    || !Objects.equals(ref.version(),actual.fileVersion()) || !Objects.equals(ref.purpose(),actual.purpose())
                    || !Objects.equals(ref.assetId(),actual.assetId()) || !Objects.equals(ref.assetRevision(),actual.assetRevision())) throw new ChatActionDispatchService.Rejected();
        }
        var sourceDomain=resolved.stream().map(x->Map.<String,Object>of("byteLength",Long.toString(x.byteLength()),
                "contentMimeType",x.contentMimeType(),"inputRef",x.inputRef(),"kind",x.kind(),"sha256",x.sha256(),
                "source",sourceDescriptor(x))).toList();
        String sourceDigest=ChatBountyInteractionV3Wire.sha(Map.of("conversationGeneration",Long.toString(scope.conversationGeneration()),
                "conversationId",scope.conversationId(),"operation",operation,"schemaVersion",1,"sources",sourceDomain,"taskId",parent.taskId()));
        var b=new ControlledImageFollowupAuthorityService.Baseline(baseline.grantId(),baseline.grantVersion(),baseline.taskVersion(),
                baseline.assignmentRevision(),baseline.requirementRevision(),baseline.requirementSha256(),baseline.targetAgentId());
        return new ControlledImageFollowupAuthorityService.PreviewCommand(parent.taskId(),scope.conversationId(),scope.conversationGeneration(),
                actionId,requestId,stepId,intentId,b,operation,action.validated().interactionOutcome().action().instruction(),
                ChatBountyInteractionV3Wire.instructionSha(action.validated().interactionOutcome().action().instruction()),
                bodyDigest.substring(7),sourceDigest,resolved);
    }

    private void requireReplay(ChatActionFinalService.BoundAction action,ChatTypedDeliberationStore.Admission prior,
            String requestId,String stepId,String intentId,String executionId,String digest,String catalog) {
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
                || link==null || !stepId.equals(link.stepId()) || !intentId.equals(link.executionIntentId()) || !executionId.equals(link.executionId())
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

    private static ChatTypedInspectionWire.SourceSelector selector(Map<String,Object> source) {
        if (!(source.get("selector") instanceof Map<?,?> s)) throw conflict();
        return ChatTypedInspectionWire.validateSelector(new ChatTypedInspectionWire.SourceSelector((String)s.get("kind"),
                (String)s.get("fileId"),(String)s.get("version"),(String)s.get("purpose"),(String)s.get("assetId"),(String)s.get("assetRevision")));
    }

    private static Map<String,Object> sourceDescriptor(ControlledImageFollowupAuthorityService.Source x) {
        if ("TASK_LINKED_WORKSPACE_VERSION".equals(x.kind())) return Map.of("kind",x.kind(),"fileId",x.fileId(),
                "version",Integer.toString(x.fileVersion()),"purpose",x.purpose());
        return Map.of("assetId",x.assetId(),"assetRevision",Long.toString(x.assetRevision()),"conversationGeneration",Long.toString(x.conversationGeneration()),
                "conversationId",x.conversationId(),"kind",x.kind(),"producerExecutionId",x.producerExecutionId(),"producerOutputId",x.producerOutputId(),
                "producerRequestId",x.producerRequestId(),"producerRunId",x.producerRunId(),"producerStepId",x.producerStepId());
    }
    private static String stable(String domain,String actionId) { return ChatBountyInteractionV3Wire.shaText(domain+"\n"+actionId); }
    private static ChatDeliberationException conflict() { return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Action execution changed"); }
    private static ChatDeliberationException persistence() { return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Unable to persist action execution"); }
}
