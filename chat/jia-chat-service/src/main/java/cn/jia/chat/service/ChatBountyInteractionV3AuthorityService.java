package cn.jia.chat.service;

import cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Chat-owned schema-3 orchestration. Agent remains independent from Chat storage and locks. */
@Service
public class ChatBountyInteractionV3AuthorityService {
    private final ChatBountyInteractionV3PreviewService previews;
    private final ControlledImageFollowupAuthorityService authorities;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final ChatMessageDao messages;
    private final ChatDeliberationDao deliberation;
    private final ChatInteractionStepStore steps;
    private final JuyitingConversationScopeService scopeService;
    private final ChatConversationEventBroker broker;

    public ChatBountyInteractionV3AuthorityService(ChatBountyInteractionV3PreviewService previews,
            ControlledImageFollowupAuthorityService authorities, ChatBountyBindingStore bindings,
            ChatConversationDao conversations, ChatMessageDao messages,
            ChatDeliberationDao deliberation, ChatInteractionStepStore steps,
            JuyitingConversationScopeService scopeService, ChatConversationEventBroker broker) {
        this.previews=Objects.requireNonNull(previews); this.authorities=Objects.requireNonNull(authorities);
        this.bindings=Objects.requireNonNull(bindings); this.conversations=Objects.requireNonNull(conversations);
        this.messages=Objects.requireNonNull(messages); this.deliberation=Objects.requireNonNull(deliberation);
        this.steps=Objects.requireNonNull(steps); this.scopeService=Objects.requireNonNull(scopeService);
        this.broker=Objects.requireNonNull(broker);
    }

    @Transactional(readOnly=true, rollbackFor=Exception.class)
    public ChatBountyInteractionV3Wire.PreviewData preview(String tenant, ServerResolvedSender sender,
            String conversationId, String interactionKey, ChatBountyInteractionV3Wire.IntentValue intent) {
        return previewData(previews.prepare(tenant,sender,conversationId,interactionKey,intent,false));
    }

    public ControlledImageFollowupAuthorityDTO issue(String tenant, ServerResolvedSender sender,
            String conversationId, String issueKey, ChatBountyInteractionV3Wire.Issue issue) {
        requireSender(tenant,sender,conversationId,issueKey);
        String issueDigest=ChatBountyInteractionV3Wire.sha(issue.canonical());
        var scope=authorityScope(tenant,sender);
        // ACK-loss replay precedes all current binding, baseline, policy and source validation.
        try {
            return authorities.reconcileIssue(scope,issue.intent().taskId(),conversationId,issueKey,issueDigest);
        } catch (ControlledImageFollowupAuthorityService.Failure absent) {
            if (absent.reason()!=ControlledImageFollowupAuthorityService.Reason.NOT_FOUND_OR_FORBIDDEN) throw absent;
        }
        var prepared=previews.prepare(tenant,sender,conversationId,issue.interactionIdempotencyKey(),issue.intent(),false);
        var expected=new ControlledImageFollowupAuthorityService.ExpectedPreview(
                issue.expectedPreview().ownerPayloadSha256(),issue.expectedPreview().instructionSha256(),
                issue.expectedPreview().sourceSnapshotSha256(),issue.expectedPreview().modelId(),
                issue.expectedPreview().custody(),issue.expectedPreview().operatorPolicyRevision());
        var requested=new ControlledImageFollowupAuthorityService.ProviderExpectation(issue.bindingId(),
                issue.bindingEpoch(),issue.expectedPreview().modelId(),issue.expectedPreview().custody(),
                issue.expectedPreview().operatorPolicyRevision());
        return authorities.issue(scope,new ControlledImageFollowupAuthorityService.IssueCommand(
                prepared.command(),issueKey,issueDigest,requested,expected,issue.acknowledgement()),()->{
            var locked=previews.prepare(tenant,sender,conversationId,issue.interactionIdempotencyKey(),issue.intent(),true);
            requireSamePrepared(prepared,locked);
        });
    }

    @Transactional(readOnly=true, rollbackFor=Exception.class)
    public ControlledImageFollowupAuthorityDTO getIssue(String tenant,ServerResolvedSender sender,
            String conversationId,String issueKey) {
        String taskId=requireConversationTask(tenant,sender,conversationId);
        return authorities.getByIssueKey(authorityScope(tenant,sender),taskId,conversationId,issueKey);
    }

    public ControlledImageFollowupAuthorityDTO revoke(String tenant,ServerResolvedSender sender,
            String conversationId,String consentId,String revokeKey,
            ChatBountyInteractionV3Wire.RevokeBody body) {
        String taskId=requireConversationTask(tenant,sender,conversationId);
        String digest=ChatBountyInteractionV3Wire.sha(Map.of("consentId",consentId,
                "conversationId",conversationId,"idempotencyKey",revokeKey,
                "operationGrantId",body.operationGrantId(),"schemaVersion",2,
                "expectedConsentVersion",Long.toString(body.expectedConsentVersion()),
                "expectedOperationGrantVersion",Long.toString(body.expectedOperationGrantVersion())));
        return authorities.revoke(authorityScope(tenant,sender),
                new ControlledImageFollowupAuthorityService.RevokeCommand(taskId,conversationId,
                        consentId,body.operationGrantId(),body.expectedConsentVersion(),
                        body.expectedOperationGrantVersion(),revokeKey,digest));
    }

    @Transactional(readOnly=true, rollbackFor=Exception.class)
    public ChatBountyInteractionV3Wire.AcceptedData getInteraction(String tenant,
            ServerResolvedSender sender,String conversationId,String interactionKey) {
        requireSender(tenant,sender,conversationId,interactionKey);
        String requestId=stable("mmd-interaction-request",tenant,sender.clientId(),sender.jiacn(),interactionKey);
        ChatRequestEntity prior=deliberation.findRequest(tenant,sender.jiacn(),sender.clientId(),requestId);
        if(prior==null||!conversationId.equals(prior.getConversationId()))throw notFound();
        var step=steps.findStep(tenant,sender.jiacn(),sender.clientId(),requestId,1,1);
        if(step==null)throw unavailable();
        var authority=authorities.getByInteractionKey(authorityScope(tenant,sender),step.taskId(),conversationId,interactionKey);
        return projection(prior,step,authority,false);
    }

    private ChatBountyInteractionV3Wire.AcceptedData projection(ChatRequestEntity request,
            ChatInteractionStepStore.Step step,ControlledImageFollowupAuthorityDTO authority,boolean replay) {
        var link=steps.findLink(step.tenantId(),step.ownerJiacn(),step.clientId(),step.stepId());
        if(link==null||!authority.executionIntentId().equals(link.executionIntentId())
                ||request.getUserMessageId()==null||request.getStateVersion()==null)throw unavailable();
        long cursor=deliberation.eventHighWatermark(step.tenantId(),step.ownerJiacn(),step.clientId(),
                step.conversationId(),step.conversationGeneration());
        return new ChatBountyInteractionV3Wire.AcceptedData(3,request.getRequestId(),
                Long.toString(request.getUserMessageId()),step.stepId(),link.executionIntentId(),
                authority.consentId(),authority.operationGrantId(),request.getAggregateState(),
                Long.toString(request.getStateVersion()),Long.toString(cursor),
                "/chat/requests/"+request.getRequestId(),replay);
    }

    private String requireConversationTask(String tenant,ServerResolvedSender sender,String conversationId) {
        requireSender(tenant,sender,conversationId,"context");
        ChatConversationEntity row=conversations.findScopedById(sender.jiacn(),sender.clientId(),conversationId);
        if(row==null||row.getTaskId()==null||row.getLifecycleGeneration()==null||row.getLifecycleGeneration()<1
                ||row.getDeletedAt()!=null||!tenant.equals(row.getTenantId())
                ||!"juyiting".equals(row.getConversationType())||!"bounty".equals(row.getConversationScopeType())
                ||!("task:"+row.getTaskId()).equals(row.getConversationScopeKey()))throw notFound();
        return row.getTaskId();
    }

    private static ChatBountyInteractionV3Wire.PreviewData previewData(
            ChatBountyInteractionV3PreviewService.Prepared p) {
        var c=p.command();var v=p.authorityPreview();
        List<ControlledImageFollowupAuthorityDTO.Source> sources=c.sources().stream().map(
                ChatBountyInteractionV3AuthorityService::sourceView).toList();
        return new ChatBountyInteractionV3Wire.PreviewData(3,c.requestId(),c.stepId(),c.executionIntentId(),
                p.ownerPayloadSha256(),p.instructionSha256(),p.sourceSnapshotSha256(),
                Long.toString(c.conversationGeneration()),Long.toString(c.baseline().taskVersion()),
                Long.toString(c.baseline().assignmentRevision()),Long.toString(c.baseline().grantVersion()),
                Long.toString(c.baseline().requirementRevision()),c.baseline().targetAgentId(),c.operation(),
                sources,new ControlledImageFollowupAuthorityDTO.ProviderBinding(v.provider().bindingId(),
                Long.toString(v.provider().bindingEpoch())),v.provider().modelId(),v.provider().custody(),
                v.provider().operatorPolicyRevision(),v.pricingMode(),v.maxOutboundRequestAttempts());
    }

    private static ControlledImageFollowupAuthorityDTO.Source sourceView(
            ControlledImageFollowupAuthorityService.Source s) {
        return new ControlledImageFollowupAuthorityDTO.Source(s.inputRef(),s.kind(),s.fileId(),
                s.fileVersion()==null?null:Integer.toString(s.fileVersion()),s.purpose(),s.conversationId(),
                s.conversationGeneration()==null?null:Long.toString(s.conversationGeneration()),s.assetId(),
                s.assetRevision()==null?null:Long.toString(s.assetRevision()),s.producerRequestId(),
                s.producerRequestRevision()==null?null:Long.toString(s.producerRequestRevision()),
                s.producerStepId(),s.producerExecutionId(),s.producerRunId(),s.producerOutputId(),
                s.contentMimeType(),Long.toString(s.byteLength()),s.sha256());
    }

    private static String runtimeDigest(ControlledImageFollowupAuthorityService.PreviewCommand command,
            String executionId,String runId) {
        List<Map<String,Object>> inputs=new ArrayList<>();
        for(var source:command.sources()) {
            Map<String,Object> descriptor=new LinkedHashMap<>();
            descriptor.put("kind",source.kind());
            if("TASK_LINKED_WORKSPACE_VERSION".equals(source.kind())) {
                descriptor.put("fileId",source.fileId());descriptor.put("purpose",source.purpose());
                descriptor.put("version",Integer.toString(source.fileVersion()));
            } else {
                descriptor.put("assetId",source.assetId());descriptor.put("assetRevision",Long.toString(source.assetRevision()));
                descriptor.put("conversationGeneration",Long.toString(source.conversationGeneration()));
                descriptor.put("conversationId",source.conversationId());descriptor.put("producerExecutionId",source.producerExecutionId());
                descriptor.put("producerOutputId",source.producerOutputId());descriptor.put("producerRequestId",source.producerRequestId());
                descriptor.put("producerRunId",source.producerRunId());descriptor.put("producerStepId",source.producerStepId());
            }
            inputs.add(Map.of("byteLength",Long.toString(source.byteLength()),"contentMimeType",source.contentMimeType(),
                    "inputRef",source.inputRef(),"sha256",source.sha256(),"source",descriptor));
        }
        return ChatBountyInteractionV3Wire.sha(Map.of("conversationId",command.conversationId(),
                "executionId",executionId,"inputs",inputs,"noReferencedMaterials",inputs.isEmpty(),
                "operation",command.operation(),"runId",runId,"schemaVersion",1,"taskId",command.taskId()));
    }

    private void publishAfterCommit(String conversationId,long generation,String owner,String client,
            String tenant,ChatConversationEventEntity event,String requestId,Long messageId,String stepId) {
        if(!TransactionSynchronizationManager.isSynchronizationActive())return;
        Map<String,Object> frame=Map.of("type","interaction.state_changed","eventId",event.getEventId(),
                "eventVersion",event.getEventSequence(),"requestId",requestId,"userMessageId",
                Long.toString(messageId),"stepId",stepId,"state","PLANNING");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){broker.publishIfSubscribed(conversationId,generation,()->{
                var live=conversations.findScopedById(owner,client,conversationId);
                return live!=null&&live.getDeletedAt()==null&&tenant.equals(live.getTenantId())
                        &&Objects.equals(generation,live.getLifecycleGeneration());},frame);}
        });
    }

    private static void requireSamePrepared(ChatBountyInteractionV3PreviewService.Prepared a,
            ChatBountyInteractionV3PreviewService.Prepared b) {
        if(!Objects.equals(a.command(),b.command())||!Objects.equals(a.authorityPreview(),b.authorityPreview())
                ||!Objects.equals(a.ownerPayloadSha256(),b.ownerPayloadSha256())
                ||!Objects.equals(a.instructionSha256(),b.instructionSha256())
                ||!Objects.equals(a.sourceSnapshotSha256(),b.sourceSnapshotSha256()))throw conflict();
    }
    private static void requireConversation(ChatConversationEntity c,String tenant,ServerResolvedSender sender,
            String id,ChatBountyInteractionV3Wire.IntentValue i) {
        if(c==null||c.getId()==null||!id.equals(Long.toString(c.getId()))||!tenant.equals(c.getTenantId())
                ||!sender.jiacn().equals(c.getJiacn())||!sender.clientId().equals(c.getClientId())
                ||c.getDeletedAt()!=null||!"juyiting".equals(c.getConversationType())
                ||!"bounty".equals(c.getConversationScopeType())||!i.taskId().equals(c.getTaskId())
                ||!("task:"+i.taskId()).equals(c.getConversationScopeKey())
                ||!Objects.equals(i.conversationGeneration(),c.getLifecycleGeneration()))throw notFound();
    }
    private static ControlledImageFollowupAuthorityService.Scope authorityScope(String tenant,ServerResolvedSender s){return new ControlledImageFollowupAuthorityService.Scope(tenant,s.clientId(),s.jiacn());}
    private static void requireSender(String tenant,ServerResolvedSender sender,String conversation,String key){if(!"0".equals(tenant)||sender==null||!"user".equals(sender.type())||!exact(sender.jiacn(),50)||!exact(sender.clientId(),50)||!exact(conversation,100)||!exact(key,100))throw invalid();}
    private static boolean exact(String v,int max){return v!=null&&!v.isBlank()&&v.equals(v.strip())&&v.codePointCount(0,v.length())<=max&&v.chars().noneMatch(Character::isISOControl);}
    private static String stable(String prefix,String...fields){StringBuilder b=new StringBuilder(prefix);for(String f:fields)b.append('\n').append(f.length()).append(':').append(f);return ChatBountyInteractionV3Wire.shaText(b.toString());}
    private static ChatDeliberationException invalid(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Invalid schema-3 bounty follow-up");}
    private static ChatDeliberationException notFound(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Bounty follow-up is unavailable");}
    private static ChatDeliberationException conflict(){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Bounty follow-up changed");}
    private static ChatDeliberationException unavailable(){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Bounty follow-up is unavailable");}
}
