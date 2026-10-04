package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Task-root-first admission for natural typed discussion and durable clarification replies. */
@Service
public class ChatTypedDiscussionAdmissionService {
    private static final String ADMISSION_STATE = "ADMITTED";
    private static final long ADMISSION_STATE_VERSION = 0L;

    private final AgentTaskMutationTransaction taskMutations;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final JuyitingConversationScopeService scopes;
    private final ChatDeliberationService deliberation;
    private final ChatDeliberationDao events;
    private final ChatTypedDeliberationContextService contexts;
    private final ChatTypedDeliberationService typed;
    private final ChatActionFinalService finals;

    public ChatTypedDiscussionAdmissionService(AgentTaskMutationTransaction taskMutations,
            ChatBountyBindingStore bindings, ChatConversationDao conversations,
            JuyitingConversationScopeService scopes, ChatDeliberationService deliberation,
            ChatDeliberationDao events, ChatTypedDeliberationContextService contexts,
            ChatTypedDeliberationService typed, ChatActionFinalService finals) {
        this.taskMutations=Objects.requireNonNull(taskMutations);this.bindings=Objects.requireNonNull(bindings);
        this.conversations=Objects.requireNonNull(conversations);this.scopes=Objects.requireNonNull(scopes);
        this.deliberation=Objects.requireNonNull(deliberation);this.events=Objects.requireNonNull(events);
        this.contexts=Objects.requireNonNull(contexts);this.typed=Objects.requireNonNull(typed);this.finals=Objects.requireNonNull(finals);
    }

    @Transactional(rollbackFor=Exception.class)
    public ChatTypedDeliberationWire.Accepted admit(String tenantId, ServerResolvedSender sender,
            String conversationId, String idempotencyKey, ChatTypedDeliberationWire.DiscussionCommand command) {
        if(!"0".equals(tenantId)||sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type())
                ||!exact(sender.jiacn(),50)||!exact(sender.clientId(),50)||!exact(conversationId,100)
                ||!exact(idempotencyKey,100)||command==null)throw invalid();
        String owner=sender.jiacn(),client=sender.clientId();
        return taskMutations.executeWithLockedTaskRootInOwnerScope(tenantId,client,owner,command.taskId(),root->{
            ChatBountyBindingStore.Binding binding;
            try { binding=bindings.lock(new ChatBountyBindingStore.Scope(tenantId,owner,client),command.taskId()); }
            catch (IllegalStateException missing) { throw unavailable(); }
            if(binding==null||binding.conversationId()==null||!conversationId.equals(Long.toString(binding.conversationId()))
                    ||binding.assignmentRevision()!=command.expectedAssignmentRevision()||root==null
                    ||root.getTaskVersion()==null||root.getTaskVersion()<command.expectedAssignmentRevision())throw conflict("Bounty assignment changed");
            ChatConversationEntity conversation=conversations.lockScopedById(owner,client,conversationId);
            if(conversation==null||conversation.getId()==null||!conversationId.equals(Long.toString(conversation.getId()))
                    ||!tenantId.equals(conversation.getTenantId())||!client.equals(conversation.getClientId())
                    ||!owner.equals(conversation.getJiacn())||!command.taskId().equals(conversation.getTaskId())
                    ||!"juyiting".equals(conversation.getConversationType())||!"bounty".equals(conversation.getConversationScopeType())
                    ||!("task:"+command.taskId()).equals(conversation.getConversationScopeKey())
                    ||conversation.getDeletedAt()!=null||conversation.getLifecycleGeneration()==null
                    ||conversation.getLifecycleGeneration()<1)throw unavailable();
            List<String> targets=scopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds());
            if(targets.size()!=1||!targets.getFirst().equals(root.getAssignedAgentId()))throw conflict("Bounty target changed");
            long generation=conversation.getLifecycleGeneration();
            var storeScope=new ChatTypedDeliberationStore.Scope(tenantId,owner,client,conversationId,generation);
            String bodyDigest=bodyDigest(command);
            var prior=typed.store().findAdmissionByKey(storeScope,idempotencyKey,true);
            if(prior!=null){
                if(!bodyDigest.equals(prior.bodyDigest()))throw conflict("Idempotency key body changed");
                return receipt(prior,true);
            }
            Parent parent=parent(storeScope,command);
            List<ChatTypedDeliberationWire.SourceSelector> selectors=command.sourceSelectors();
            if("CLARIFICATION_REPLY".equals(command.intent())&&selectors.isEmpty())selectors=selectors(parent.outcome().sourceCatalogJson());
            var context=contexts.resolve(new ChatTypedDeliberationContextService.Scope(tenantId,owner,client,conversationId,generation),
                    command.taskId(),targets.getFirst(),selectors);
            String requestDigest=requestDigest(command,context.sourceCatalogJson());
            String requestId=stable("mmd-typed-request",tenantId,client,owner,conversationId,idempotencyKey);
            ChatMessageDTO input=new ChatMessageDTO();input.setConversationId(conversationId);input.setConversationType("juyiting");
            input.setConversationScopeType("bounty");input.setConversationScopeKey("task:"+command.taskId());input.setTaskId(command.taskId());
            input.setContent(command.content());input.setRequestId(requestId);input.setRequestRevision(1L);
            input.setTargetAgentId(targets.getFirst());input.setTargetAgentIds(List.copyOf(targets));
            var scope=new JuyitingConversationScope("bounty","task:"+command.taskId(),command.taskId(),targets.getFirst(),List.copyOf(targets),List.copyOf(targets));
            var admitted=deliberation.admit(tenantId,sender,conversationId,generation,scope,
                    InteractionRoute.CHAT,input,null,context.facts(),admissionFacts(command,context.selectors(),deliveryParent(storeScope,command,parent)));
            List<String> turnIds=admitted.dispatches().stream().map(ChatDeliberationService.Dispatch::turnId).toList();
            if(turnIds.size()!=1)throw unavailable();
            long now=System.currentTimeMillis();
            if(parent.pending()!=null){int updated=typed.store().answerPending(parent.pending(),command.expectedPendingQuestionStateVersion(),requestId,idempotencyKey,bodyDigest,now);if(updated!=1)throw conflict("Pending question state changed");
                ChatTurnEntity turn=events.findTurn(tenantId,owner,client,turnIds.getFirst());if(turn==null)throw unavailable();
                deliberation.persistTypedQuestionAnswered(turn,parent.pending().pendingQuestionId(),parent.pending().stateVersion()+1,requestId,parent.outcome().outcomeId(),now);
            }
            long cursor=events.eventHighWatermark(tenantId,owner,client,conversationId,generation);
            String admissionId=stable("mmd-typed-admission",tenantId,client,owner,conversationId,idempotencyKey);
            var row=new ChatTypedDeliberationStore.Admission(admissionId,storeScope,idempotencyKey,requestDigest,bodyDigest,command.intent(),
                    command.taskId(),command.expectedAssignmentRevision(),command.parentOutcomeId(),command.pendingQuestionId(),
                    requestId,1,Long.parseLong(admitted.userMessageId()),CanonicalContextJson.write(turnIds),context.sourceCatalogJson(),
                    ADMISSION_STATE,ADMISSION_STATE_VERSION,cursor,now);
            if(typed.store().insertAdmission(row)!=1)throw persistence("Unable to persist typed admission");
            return receipt(row,false);
        });
    }

    private Parent parent(ChatTypedDeliberationStore.Scope scope,ChatTypedDeliberationWire.DiscussionCommand command){
        if(command.parentOutcomeId()==null)return new Parent(null,null);
        var outcome=typed.requireParent(scope,command.parentOutcomeId());
        if(!command.taskId().equals(outcome.taskId())||!scope.equals(outcome.scope())
                ||outcome.assignmentRevision()!=command.expectedAssignmentRevision())throw unavailable();
        if("CLARIFICATION_REPLY".equals(command.intent())){
            if(!"CLARIFY".equals(outcome.kind()))throw unavailable();
            var pending=typed.requirePending(scope,command.pendingQuestionId());
            if(!outcome.outcomeId().equals(pending.outcomeId())||!"OPEN".equals(pending.state())
                    ||pending.stateVersion()!=command.expectedPendingQuestionStateVersion()
                    ||command.expectedParentStateVersion()!=pending.stateVersion())throw conflict("Pending question state changed");
            return new Parent(outcome,pending);
        }
        long version="CLARIFY".equals(outcome.kind())?typed.requirePending(scope,stable("typed-question",outcome.outcomeId())).stateVersion():0;
        if(command.expectedParentStateVersion()==null||command.expectedParentStateVersion()!=version)throw conflict("Parent outcome state changed");
        return new Parent(outcome,null);
    }

    private ChatTypedDeliberationWire.Accepted receipt(ChatTypedDeliberationStore.Admission row,boolean replay){
        List<String> turns=parseStrings(row.turnIdsJson());
        if(turns.size()!=1||!ADMISSION_STATE.equals(row.state())||row.stateVersion()!=ADMISSION_STATE_VERSION)
            throw persistence("Stored typed receipt is invalid");
        return new ChatTypedDeliberationWire.Accepted(1,row.intent(),row.requestId(),
                Long.toString(row.userMessageId()),turns,row.state(),Long.toString(row.stateVersion()),Long.toString(row.eventCursor()),
                "/chat/requests/"+row.requestId(),"/chat/conversations/"+row.scope().conversationId()+"/requests/"+row.requestId()+"/typed-outcome",replay,row.pendingQuestionId());
    }
    /** Parent linkage alone is not replacement intent. Advertise only an actual completed
     * marked text final; the new Agent reply must explicitly choose a relation. */
    private Map<String,Object> deliveryParent(ChatTypedDeliberationStore.Scope scope,
            ChatTypedDeliberationWire.DiscussionCommand command, Parent parent) {
        var row=parent.outcome();
        if(row==null)return null;
        Map<String,Object> inherited=null;
        if("CLARIFICATION_REPLY".equals(command.intent())) {
            inherited=finals.clarificationDeliveryParent(scope,row);
            if(inherited==null)return null;
            row=typed.requireParent(scope,(String)inherited.get("outcomeId"));
            if(!command.taskId().equals(row.taskId())||!scope.equals(row.scope())
                    ||!inherited.get("finalDigest").equals(row.finalDigest()))throw unavailable();
        } else if(!"DISCUSSION".equals(command.intent()))return null;
        if(!"ANSWER".equals(row.kind())){if(inherited!=null)throw unavailable();return null;}
        var turn=events.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.turnId());
        if(turn==null||!"CHAT".equals(turn.getRoute())
                ||!("FINAL_PERSISTED".equals(turn.getState())||"PUBLISHED".equals(turn.getState()))
                ||row.assignmentRevision()!=command.expectedAssignmentRevision())throw unavailable();
        var projection=finals.readIfV3(scope,row.requestId(),row.turnId(),row.requestRevision(),"CHAT");
        if(projection==null)return null;
        if(!"READY".equals(projection.get("state"))||!(projection.get("outcome") instanceof Map<?,?> view)
                ||!row.outcomeId().equals(view.get("outcomeId"))||!row.finalDigest().equals(view.get("finalDigest")))throw unavailable();
        if(inherited!=null&&!Boolean.TRUE.equals(view.get("deliverable")))throw unavailable();
        return Boolean.TRUE.equals(view.get("deliverable"))?Map.of("outcomeId",row.outcomeId(),"finalDigest",row.finalDigest()):null;
    }

    private static Map<String,Object> admissionFacts(ChatTypedDeliberationWire.DiscussionCommand command,
            List<ChatTypedDeliberationWire.SourceSelector> selectors, Map<String,Object> deliveryParent) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("schemaVersion",1);value.put("intent",command.intent());
        if(deliveryParent!=null)value.put("deliveryParent",deliveryParent);
        value.put("sourceSelectors",selectors.stream().map(ChatTypedDeliberationWire::selectorMap).toList());
        value.put("parentOutcomeId",command.parentOutcomeId());
        value.put("expectedParentStateVersion",command.expectedParentStateVersion()==null
                ?null:Long.toString(command.expectedParentStateVersion()));
        value.put("pendingQuestionId",command.pendingQuestionId());
        value.put("expectedPendingQuestionStateVersion",command.expectedPendingQuestionStateVersion()==null
                ?null:Long.toString(command.expectedPendingQuestionStateVersion()));
        return java.util.Collections.unmodifiableMap(value);
    }

    private static String bodyDigest(ChatTypedDeliberationWire.DiscussionCommand command){
        return ChatDeliberationService.digest(commandMap(command));
    }
    private static String requestDigest(ChatTypedDeliberationWire.DiscussionCommand command,String catalog){
        Map<String,Object> complete=commandMap(command);
        complete.put("sourceCatalog",ChatTypedDeliberationContextService.parseCatalog(catalog));
        return ChatDeliberationService.digest(complete);
    }
    private static Map<String,Object> commandMap(ChatTypedDeliberationWire.DiscussionCommand c){
        Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",1);m.put("intent",c.intent());
        m.put("taskId",c.taskId());m.put("expectedAssignmentRevision",Long.toString(c.expectedAssignmentRevision()));
        m.put("content",c.content());m.put("parentOutcomeId",c.parentOutcomeId());
        m.put("expectedParentStateVersion",c.expectedParentStateVersion()==null?null:Long.toString(c.expectedParentStateVersion()));
        m.put("pendingQuestionId",c.pendingQuestionId());
        m.put("expectedPendingQuestionStateVersion",c.expectedPendingQuestionStateVersion()==null?null:Long.toString(c.expectedPendingQuestionStateVersion()));
        m.put("sourceSelectors",c.sourceSelectors().stream().map(ChatTypedDeliberationWire::selectorMap).toList());return m;
    }
    @SuppressWarnings("unchecked") private static List<String> parseStrings(String json){try{Object v=JsonUtil.getMapper().readValue(json,List.class);if(v instanceof List<?> l&&l.stream().allMatch(String.class::isInstance))return(List<String>)l;}catch(Exception ignored){}throw persistence("Stored typed receipt is invalid");}
    @SuppressWarnings("unchecked") private static List<ChatTypedDeliberationWire.SourceSelector> selectors(String json){List<Map<String,Object>> rows=ChatTypedDeliberationContextService.parseCatalog(json);List<ChatTypedDeliberationWire.SourceSelector> r=new ArrayList<>();for(var row:rows){Map<String,Object> m=(Map<String,Object>)row.get("selector");r.add(new ChatTypedDeliberationWire.SourceSelector((String)m.get("kind"),(String)m.get("fileId"),(String)m.get("version"),(String)m.get("purpose"),(String)m.get("assetId"),(String)m.get("assetRevision")));}return List.copyOf(r);}
    private record Parent(ChatTypedDeliberationStore.Outcome outcome,ChatTypedDeliberationStore.PendingQuestion pending){}
    private static boolean exact(String v,int max){return v!=null&&!v.isBlank()&&v.equals(v.strip())&&v.codePointCount(0,v.length())<=max&&v.codePoints().noneMatch(Character::isISOControl);}
    private static String stable(String prefix,String...parts){return prefix.substring(0,Math.min(prefix.length(),20))+"_"+sha256(String.join("\0",parts)).substring(0,40);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ChatDeliberationException invalid(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Typed discussion is invalid");}
    private static ChatDeliberationException unavailable(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Typed discussion is unavailable");}
    private static ChatDeliberationException conflict(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,m);}
    private static ChatDeliberationException persistence(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,m);}
}
