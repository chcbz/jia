package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
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

/** Task-root-first INSPECT admission and side-effect-free original-key recovery. */
@Service
public class ChatTypedInspectionAdmissionService {
    private static final String STATE="ADMITTED";
    private final AgentTaskMutationTransaction tasks;private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;private final JuyitingConversationScopeService scopes;
    private final ChatDeliberationService deliberation;private final ChatDeliberationDao events;
    private final ChatTypedInspectionContextService contexts;private final ChatTypedDeliberationService typed;

    public ChatTypedInspectionAdmissionService(AgentTaskMutationTransaction tasks,ChatBountyBindingStore bindings,
            ChatConversationDao conversations,JuyitingConversationScopeService scopes,
            ChatDeliberationService deliberation,ChatDeliberationDao events,
            ChatTypedInspectionContextService contexts,ChatTypedDeliberationService typed){
        this.tasks=Objects.requireNonNull(tasks);this.bindings=Objects.requireNonNull(bindings);
        this.conversations=Objects.requireNonNull(conversations);this.scopes=Objects.requireNonNull(scopes);
        this.deliberation=Objects.requireNonNull(deliberation);this.events=Objects.requireNonNull(events);
        this.contexts=Objects.requireNonNull(contexts);this.typed=Objects.requireNonNull(typed);
    }

    @Transactional(rollbackFor=Exception.class)
    public ChatTypedInspectionWire.Accepted admit(String tenant,ServerResolvedSender sender,String conversationId,
            String key,ChatTypedInspectionWire.Command command){
        identity(tenant,sender,conversationId,key,command);String owner=sender.jiacn(),client=sender.clientId();
        return tasks.executeWithLockedTaskRootInOwnerScope(tenant,client,owner,command.taskId(),root->{
            ChatBountyBindingStore.Binding binding;
            try{binding=bindings.lock(new ChatBountyBindingStore.Scope(tenant,owner,client),command.taskId());}
            catch(IllegalStateException missing){throw unavailable();}
            if(root==null||binding==null||binding.conversationId()==null
                    ||!conversationId.equals(Long.toString(binding.conversationId()))
                    ||binding.assignmentRevision()!=command.expectedAssignmentRevision()
                    ||root.getTaskVersion()==null||root.getTaskVersion()<command.expectedAssignmentRevision())throw conflict("Bounty assignment changed");
            ChatConversationEntity conversation=requireConversation(tenant,owner,client,conversationId,command.taskId(),true);
            List<String> targets=scopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds());
            if(targets.size()!=1||!targets.getFirst().equals(root.getAssignedAgentId()))throw conflict("Bounty target changed");
            long generation=conversation.getLifecycleGeneration();
            var scope=new ChatTypedDeliberationStore.Scope(tenant,owner,client,conversationId,generation);
            String body=bodyDigest(command);var prior=typed.store().findAdmissionByKey(scope,key,true);
            if(prior!=null){requireInspectionAdmission(prior);if(!body.equals(prior.bodyDigest()))throw conflict("Idempotency key body changed");return receipt(prior,true);}
            Parent parent=parent(scope,command);List<ChatTypedInspectionWire.SourceSelector> selectors=command.sourceSelectors();
            if("CLARIFICATION_REPLY".equals(command.intent())&&selectors.isEmpty())selectors=selectors(parent.outcome().sourceCatalogJson());
            if(selectors.isEmpty())throw invalidSource();
            String requestId=stable("mmd-inspection-request",tenant,client,owner,conversationId,key);
            var context=contexts.resolve(new ChatTypedInspectionContextService.Scope(tenant,owner,client,
                    conversationId,generation,command.taskId(),command.expectedAssignmentRevision(),requestId,1,targets.getFirst()),selectors);
            String requestDigest=requestDigest(command,context.typedInspection());
            ChatMessageDTO input=new ChatMessageDTO();input.setConversationId(conversationId);input.setConversationType("juyiting");
            input.setConversationScopeType("bounty");input.setConversationScopeKey("task:"+command.taskId());input.setTaskId(command.taskId());
            input.setContent(command.content());input.setRequestId(requestId);input.setRequestRevision(1L);
            input.setTargetAgentId(targets.getFirst());input.setTargetAgentIds(List.copyOf(targets));
            var conversationScope=new JuyitingConversationScope("bounty","task:"+command.taskId(),command.taskId(),
                    targets.getFirst(),List.copyOf(targets),List.copyOf(targets));
            var admitted=deliberation.admitInspection(tenant,sender,conversationId,generation,conversationScope,input,null,
                    context.typedInspection(),admissionFacts(command));
            List<String> turns=admitted.dispatches().stream().map(ChatDeliberationService.Dispatch::turnId).toList();
            if(turns.size()!=1)throw persistence("Inspection admission must have one turn");long now=System.currentTimeMillis();
            if(parent.pending()!=null){int updated=typed.store().answerPending(parent.pending(),command.expectedPendingQuestionStateVersion(),
                    requestId,key,body,now);if(updated!=1)throw conflict("Pending question state changed");
                ChatTurnEntity turn=events.findTurn(tenant,owner,client,turns.getFirst());if(turn==null)throw unavailable();
                deliberation.persistTypedQuestionAnswered(turn,parent.pending().pendingQuestionId(),parent.pending().stateVersion()+1,
                        requestId,parent.outcome().outcomeId(),now);}
            long cursor=events.eventHighWatermark(tenant,owner,client,conversationId,generation);
            var row=new ChatTypedDeliberationStore.Admission(stable("mmd-inspection-admission",tenant,client,owner,conversationId,key),
                    scope,key,requestDigest,body,command.intent(),command.taskId(),command.expectedAssignmentRevision(),
                    command.parentOutcomeId(),command.pendingQuestionId(),requestId,1,Long.parseLong(admitted.userMessageId()),
                    CanonicalContextJson.write(turns),context.admissionEnvelopeJson(),STATE,0,cursor,now);
            if(typed.store().insertAdmission(row)!=1)throw persistence("Unable to persist inspection admission");
            return receipt(row,false);
        });
    }

    @Transactional(readOnly=true)
    public ChatTypedInspectionWire.Accepted recover(String tenant,ServerResolvedSender sender,String conversationId,String key){
        identity(tenant,sender,conversationId,key);
        ChatConversationEntity conversation=requireConversation(tenant,sender.jiacn(),sender.clientId(),conversationId,null,false);
        var scope=new ChatTypedDeliberationStore.Scope(tenant,sender.jiacn(),sender.clientId(),conversationId,conversation.getLifecycleGeneration());
        var row=typed.store().findAdmissionByKey(scope,key,false);if(row==null)throw unavailable();requireInspectionAdmission(row);
        return receipt(row,true);
    }

    private Parent parent(ChatTypedDeliberationStore.Scope scope,ChatTypedInspectionWire.Command command){
        if(command.parentOutcomeId()==null)return new Parent(null,null);var outcome=typed.requireParent(scope,command.parentOutcomeId());
        if(!command.taskId().equals(outcome.taskId()))throw unavailable();
        if("CLARIFICATION_REPLY".equals(command.intent())){var pending=typed.requirePending(scope,command.pendingQuestionId());
            if(!outcome.outcomeId().equals(pending.outcomeId())||!"OPEN".equals(pending.state())
                    ||pending.stateVersion()!=command.expectedPendingQuestionStateVersion()
                    ||command.expectedParentStateVersion()!=pending.stateVersion())throw conflict("Pending question state changed");
            return new Parent(outcome,pending);}
        long version="CLARIFY".equals(outcome.kind())?typed.requirePending(scope,stable("typed-question",outcome.outcomeId())).stateVersion():0;
        if(command.expectedParentStateVersion()==null||command.expectedParentStateVersion()!=version)throw conflict("Parent outcome state changed");
        return new Parent(outcome,null);
    }

    private ChatConversationEntity requireConversation(String tenant,String owner,String client,String id,String task,boolean lock){
        ChatConversationEntity row=lock?conversations.lockScopedById(owner,client,id):conversations.findScopedById(owner,client,id);
        if(row==null||row.getId()==null||!id.equals(Long.toString(row.getId()))||!tenant.equals(row.getTenantId())
                ||!owner.equals(row.getJiacn())||!client.equals(row.getClientId())||row.getDeletedAt()!=null
                ||row.getLifecycleGeneration()==null||row.getLifecycleGeneration()<1||!"juyiting".equals(row.getConversationType())
                ||!"bounty".equals(row.getConversationScopeType())||(task!=null&&(!task.equals(row.getTaskId())
                ||!("task:"+task).equals(row.getConversationScopeKey()))))throw unavailable();return row;
    }
    private void requireInspectionAdmission(ChatTypedDeliberationStore.Admission row){
        String catalog=row.sourceCatalogJson();
        if(catalog==null||catalog.isBlank())throw persistence("Stored inspection admission is invalid");
        if(catalog.stripLeading().startsWith("["))throw conflict("Idempotency key belongs to another typed contract");
        ChatTypedInspectionContextService.parseEnvelope(catalog);
    }
    private ChatTypedInspectionWire.Accepted receipt(ChatTypedDeliberationStore.Admission row,boolean replay){
        List<String> turns=parseStrings(row.turnIdsJson());if(turns.size()!=1||!STATE.equals(row.state())||row.stateVersion()!=0)throw persistence("Stored inspection receipt is invalid");
        return new ChatTypedInspectionWire.Accepted(1,row.intent(),row.requestId(),Long.toString(row.userMessageId()),turns,row.state(),
                Long.toString(row.stateVersion()),Long.toString(row.eventCursor()),"/chat/requests/"+row.requestId(),
                "/chat/conversations/"+row.scope().conversationId()+"/requests/"+row.requestId()+"/inspection-outcome",replay,row.pendingQuestionId());
    }
    private static Map<String,Object> admissionFacts(ChatTypedInspectionWire.Command c){Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",1);m.put("intent",c.intent());m.put("parentOutcomeId",c.parentOutcomeId());m.put("expectedParentStateVersion",c.expectedParentStateVersion()==null?null:Long.toString(c.expectedParentStateVersion()));m.put("pendingQuestionId",c.pendingQuestionId());m.put("expectedPendingQuestionStateVersion",c.expectedPendingQuestionStateVersion()==null?null:Long.toString(c.expectedPendingQuestionStateVersion()));return java.util.Collections.unmodifiableMap(m);}
    private static String bodyDigest(ChatTypedInspectionWire.Command c){Map<String,Object> m=commandMap(c);m.put("contract",ChatTypedInspectionWire.CONTRACT);m.put("purpose","INSPECT");return ChatDeliberationService.digest(m);}
    private static String requestDigest(ChatTypedInspectionWire.Command c,Map<String,Object> inspection){Map<String,Object> m=commandMap(c);m.put("contract",ChatTypedInspectionWire.CONTRACT);m.put("purpose","INSPECT");m.put("typedInspection",inspection);return ChatDeliberationService.digest(m);}
    private static Map<String,Object> commandMap(ChatTypedInspectionWire.Command c){Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",1);m.put("intent",c.intent());m.put("taskId",c.taskId());m.put("expectedAssignmentRevision",Long.toString(c.expectedAssignmentRevision()));m.put("content",c.content());m.put("parentOutcomeId",c.parentOutcomeId());m.put("expectedParentStateVersion",c.expectedParentStateVersion()==null?null:Long.toString(c.expectedParentStateVersion()));m.put("pendingQuestionId",c.pendingQuestionId());m.put("expectedPendingQuestionStateVersion",c.expectedPendingQuestionStateVersion()==null?null:Long.toString(c.expectedPendingQuestionStateVersion()));m.put("sourceSelectors",c.sourceSelectors().stream().map(ChatTypedInspectionWire::selectorMap).toList());return m;}
    @SuppressWarnings("unchecked") private static List<ChatTypedInspectionWire.SourceSelector> selectors(String json){
        try{Object raw=JsonUtil.getMapper().readValue(json,Object.class);List<?> list;
            if(raw instanceof List<?> v)list=v;else if(raw instanceof Map<?,?>){list=ChatTypedInspectionContextService.sources(json).stream().map(source->source.get("selector")).toList();}else throw new IllegalArgumentException();
            List<ChatTypedInspectionWire.SourceSelector> out=new ArrayList<>();for(Object item:list){Map<String,Object> m=(Map<String,Object>)(raw instanceof List<?>?((Map<String,Object>)item).get("selector"):item);out.add(new ChatTypedInspectionWire.SourceSelector((String)m.get("kind"),(String)m.get("fileId"),(String)m.get("version"),(String)m.get("purpose"),(String)m.get("assetId"),(String)m.get("assetRevision")));}return List.copyOf(out);
        }catch(Exception failure){throw persistence("Stored typed selectors are invalid");}}
    @SuppressWarnings("unchecked") private static List<String> parseStrings(String json){try{Object v=JsonUtil.getMapper().readValue(json,List.class);if(v instanceof List<?> l&&l.stream().allMatch(String.class::isInstance))return(List<String>)l;}catch(Exception ignored){}throw persistence("Stored inspection receipt is invalid");}
    private static void identity(String tenant,ServerResolvedSender sender,String conversation,String key,Object command){identity(tenant,sender,conversation,key);if(command==null)throw invalid();}
    private static void identity(String tenant,ServerResolvedSender sender,String conversation,String key){if(!"0".equals(tenant)||sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type())||!exact(sender.jiacn(),50)||!exact(sender.clientId(),50)||!exact(conversation,100)||!exact(key,100))throw invalid();}
    private static boolean exact(String v,int max){return v!=null&&!v.isBlank()&&v.equals(v.strip())&&v.codePointCount(0,v.length())<=max&&v.codePoints().noneMatch(Character::isISOControl);}
    private static String stable(String prefix,String...parts){return prefix.substring(0,Math.min(prefix.length(),24))+"_"+sha256(String.join("\0",parts)).substring(0,40);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private record Parent(ChatTypedDeliberationStore.Outcome outcome,ChatTypedDeliberationStore.PendingQuestion pending){}
    private static ChatDeliberationException invalid(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Typed inspection is invalid");}
    private static ChatDeliberationException invalidSource(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Typed inspection source selection is invalid");}
    private static ChatDeliberationException unavailable(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Typed inspection is unavailable");}
    private static ChatDeliberationException conflict(String message){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,message);}
    private static ChatDeliberationException persistence(String message){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,message);}
}
