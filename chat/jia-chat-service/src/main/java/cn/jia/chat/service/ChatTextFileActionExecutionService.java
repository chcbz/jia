package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Zero-input text files enter the existing fenced CONVERSATION execution, never CHAT tools. */
@Service
public class ChatTextFileActionExecutionService {
    private final ChatDeliberationService deliberation;
    private final ChatDeliberationDao requests;
    private final ChatTypedDeliberationStore typed;
    private final ChatInteractionStepStore steps;
    private final AgentTaskExecutionGrantService grants;
    private final PersonalWorkspaceExecutionService executions;

    public ChatTextFileActionExecutionService(ChatDeliberationService deliberation, ChatDeliberationDao requests,
            ChatTypedDeliberationStore typed, ChatInteractionStepStore steps,
            AgentTaskExecutionGrantService grants, PersonalWorkspaceExecutionService executions) {
        this.deliberation=deliberation; this.requests=requests; this.typed=typed;
        this.steps=steps; this.grants=grants; this.executions=executions;
    }

    @Transactional(propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public String admit(ChatActionFinalService.BoundAction action) {
        var parent=action.outcome(); var scope=parent.scope();
        var requested=action.validated().interactionOutcome().action();
        var capability=action.validated().dispatchFacts().availableActions().stream()
                .filter(c->c.actionId().equals(requested.actionId())).findFirst().orElseThrow(ChatTextFileActionExecutionService::conflict);
        if (!"generate-text-file".equals(requested.actionId()) || !"EXECUTE".equals(capability.kind())
                || !"GENERATE_TEXT_FILE".equals(capability.operation()) || capability.minSources()!=0
                || capability.maxSources()!=0 || !requested.sourceRefIds().isEmpty()) throw conflict();
        String target=(String)action.validated().binding().get("targetAgentId");
        String event=ChatActionFinalValidator.actionEventId(action.validated());
        String requestId=stable("text-file-request",event), stepId=stable("text-file-step",event);
        String intentId=stable("text-file-intent",event);
        String executionId=PersonalWorkspaceExecutionService.textFileExecutionId(
                new PersonalWorkspaceExecutionService.OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),intentId);
        var user=deliberation.requireActionUser(action);
        String userHash=ChatBountyInteractionV3Wire.shaText(user.getContent());
        String digest=ChatDeliberationService.digest(Map.of("schemaVersion",3,"actionRequestId",event,
                "parentOutcomeId",parent.outcomeId(),"parentFinalDigest",parent.finalDigest(),
                "userMessageId",Long.toString(user.getId()),"userContentSha256",userHash,
                "operation","GENERATE_TEXT_FILE","instruction",requested.instruction(),"sources",List.of()));
        var prior=typed.findAdmissionByKey(scope,event,true);
        if (prior!=null) {
            var request=requests.findRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId);
            var step=steps.findStep(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId,1,1);
            var link=steps.findLink(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),stepId);
            if (!scope.equals(prior.scope()) || !event.equals(prior.idempotencyKey())
                    || !digest.equals(prior.bodyDigest()) || !digest.equals(prior.requestDigest())
                    || !requestId.equals(prior.requestId()) || prior.requestRevision()!=1
                    || !parent.outcomeId().equals(prior.parentOutcomeId()) || !parent.taskId().equals(prior.taskId())
                    || parent.assignmentRevision()!=prior.assignmentRevision() || prior.userMessageId()!=user.getId()
                    || !"ADMITTED".equals(prior.state()) || prior.stateVersion()!=0 || !"DISCUSSION".equals(prior.intent())
                    || !"[]".equals(prior.turnIdsJson()) || !"[]".equals(prior.sourceCatalogJson())
                    || request==null || !scope.tenantId().equals(request.getTenantId())
                    || !scope.clientId().equals(request.getClientId()) || !scope.ownerJiacn().equals(request.getOwnerJiacn())
                    || !requestId.equals(request.getRequestId()) || !digest.equals(request.getRequestDigest())
                    || !Objects.equals(user.getId(),request.getUserMessageId())
                    || !scope.conversationId().equals(request.getConversationId())
                    || !Objects.equals(scope.conversationGeneration(),request.getConversationGeneration())
                    || !Objects.equals(1L,request.getRequestRevision())
                    || step==null || !stepId.equals(step.stepId()) || !requestId.equals(step.requestId())
                    || !scope.tenantId().equals(step.tenantId()) || !scope.clientId().equals(step.clientId())
                    || !scope.ownerJiacn().equals(step.ownerJiacn()) || !scope.conversationId().equals(step.conversationId())
                    || scope.conversationGeneration()!=step.conversationGeneration() || step.requestRevision()!=1 || step.stepNumber()!=1
                    || !parent.taskId().equals(step.taskId()) || parent.assignmentRevision()!=step.assignmentRevision()
                    || !target.equals(step.targetAgentId()) || !"EXECUTE".equals(step.kind())
                    || !digest.equals(step.inputSnapshotDigest())
                    || link==null || !stepId.equals(link.stepId()) || !intentId.equals(link.executionIntentId())
                    || !scope.tenantId().equals(link.tenantId()) || !scope.clientId().equals(link.clientId())
                    || !scope.ownerJiacn().equals(link.ownerJiacn()) || !executionId.equals(link.executionId())) throw conflict();
            return requestId; // Immutable replay. Never repeat create or demand a newly live runtime.
        }
        if (requests.findRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId)!=null) throw conflict();
        var grant=grants.resolveAndAdmit(new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                parent.taskId(),parent.assignmentRevision(),target,"GENERATE_TEXT_FILE",false);
        if (grant==null || !"GENERATE_TEXT_FILE".equals(grant.operation()) || !target.equals(grant.targetAgentId())
                || grant.assignmentRevision()!=parent.assignmentRevision() || !grant.inputs().isEmpty()) throw conflict();
        var execution=executions.createConversation(new PersonalWorkspaceExecutionService.OwnerScope(
                scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                new PersonalWorkspaceExecutionService.ConversationCreate(scope.conversationId(),parent.taskId(),target,
                        intentId,grant.grantId(),grant.grantVersion(),parent.assignmentRevision(),"GENERATE_TEXT_FILE",
                        requested.instruction(),"text/plain",List.of(),false,1));
        if (execution==null || !executionId.equals(execution.executionId()) || !"CONVERSATION".equals(execution.executionMode())
                || !parent.taskId().equals(execution.taskId()) || !scope.conversationId().equals(execution.conversationId())
                || !target.equals(execution.targetAgentId()) || !"text/plain".equals(execution.outputContentMimeType())) throw conflict();
        var currentUser=deliberation.requireActionUser(action);
        if (!Objects.equals(user.getId(),currentUser.getId())
                || !userHash.equals(ChatBountyInteractionV3Wire.shaText(currentUser.getContent()))) throw conflict();
        long now=System.currentTimeMillis();
        var child=new ChatRequestEntity().setTenantId(scope.tenantId()).setOwnerJiacn(scope.ownerJiacn())
                .setClientId(scope.clientId()).setRequestId(requestId).setRequestRevision(1L).setRequestDigest(digest)
                .setConversationId(scope.conversationId()).setConversationGeneration(scope.conversationGeneration())
                .setUserMessageId(user.getId()).setAggregateState("RUNNING").setStateVersion(0L).setCreatedAt(now).setUpdatedAt(now);
        if (requests.insertRequest(child)!=1 || child.getId()==null) throw conflict();
        var step=new ChatInteractionStepStore.Step(stepId,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId,1,1,
                scope.conversationId(),scope.conversationGeneration(),parent.taskId(),parent.assignmentRevision(),
                grant.grantId(),grant.grantVersion(),target,"EXECUTE","RUNNING",0,digest,now,now);
        if (steps.insertStep(step)!=1 || steps.insertLink(new ChatInteractionStepStore.ExecutionLink(intentId,
                scope.tenantId(),scope.ownerJiacn(),scope.clientId(),stepId,execution.executionId(),"RUNNING",0,now,now))!=1) throw conflict();
        var admission=new ChatTypedDeliberationStore.Admission("action-admit_"+event.substring(4),scope,event,digest,digest,
                "DISCUSSION",parent.taskId(),parent.assignmentRevision(),parent.outcomeId(),null,requestId,1,user.getId(),
                "[]","[]","ADMITTED",0,requests.eventHighWatermark(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),
                scope.conversationId(),scope.conversationGeneration()),now);
        if (typed.insertAdmission(admission)!=1) throw conflict();
        deliberation.persistActionStarted(action,requestId,"EXECUTE",now);
        return requestId;
    }
    private static String stable(String domain,String event) { return ChatBountyInteractionV3Wire.shaText(domain+"\n"+event); }
    private static ChatDeliberationException conflict() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Text file action changed");
    }
}
