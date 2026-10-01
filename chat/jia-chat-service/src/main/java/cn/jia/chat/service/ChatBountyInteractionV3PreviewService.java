package cn.jia.chat.service;
import cn.jia.agent.service.*;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** Read-only owner preview. Every fact is re-derived; this projection grants nothing. */
@Service
public class ChatBountyInteractionV3PreviewService {
 private final ChatConversationDao conversations;private final JuyitingConversationScopeService scopes;
 private final AgentTaskExecutionGrantService grants;private final ChatConversationAssetSourceResolver sources;
 private final ControlledImageFollowupAuthorityService authority;private final cn.jia.chat.deliberation.ChatBountyBindingStore bindings;
 public ChatBountyInteractionV3PreviewService(ChatConversationDao conversations,JuyitingConversationScopeService scopes,
   AgentTaskExecutionGrantService grants,ChatConversationAssetSourceResolver sources,ControlledImageFollowupAuthorityService authority,
   cn.jia.chat.deliberation.ChatBountyBindingStore bindings){this.conversations=Objects.requireNonNull(conversations);this.scopes=Objects.requireNonNull(scopes);this.grants=Objects.requireNonNull(grants);this.sources=Objects.requireNonNull(sources);this.authority=Objects.requireNonNull(authority);this.bindings=Objects.requireNonNull(bindings);}
 public record Prepared(ControlledImageFollowupAuthorityService.PreviewCommand command,
   ControlledImageFollowupAuthorityService.Preview authorityPreview,String ownerPayloadSha256,String instructionSha256,String sourceSnapshotSha256) { }
 @Transactional(readOnly=true,rollbackFor=Exception.class)
 public ChatBountyInteractionV3Wire.ContextData context(String tenant,ServerResolvedSender sender,String conversation){
  if(!"0".equals(tenant)||sender==null||!"user".equals(sender.type()))throw notFound();
  try {
   ChatConversationEntity observed=conversations.findScopedById(sender.jiacn(),sender.clientId(),conversation);
   if(observed==null)throw notFound();
   if(observed.getId()==null||!conversation.equals(Long.toString(observed.getId()))
     ||observed.getTaskId()==null||observed.getLifecycleGeneration()==null
     ||observed.getLifecycleGeneration()<1||observed.getDeletedAt()!=null
     ||!tenant.equals(observed.getTenantId())||!"juyiting".equals(observed.getConversationType())
     ||!"bounty".equals(observed.getConversationScopeType())
     ||!("task:"+observed.getTaskId()).equals(observed.getConversationScopeKey()))throw conflict();
   List<String> targets=scopes.parsePersistedTargetAgentIds(observed.getTargetAgentIds());
   if(targets.size()!=1)throw conflict();String target=targets.getFirst();
   var current=grants.currentFollowupContext(new AgentTaskExecutionGrantService.Scope(tenant,
     sender.clientId(),sender.jiacn()),observed.getTaskId(),target);
   var binding=bindings.lock(new cn.jia.chat.deliberation.ChatBountyBindingStore.Scope(
     tenant,sender.jiacn(),sender.clientId()),observed.getTaskId());
   if(binding==null)throw conflict();
   ChatConversationEntity locked=conversations.lockScopedById(
     sender.jiacn(),sender.clientId(),conversation);
   if(binding.conversationId()==null||!conversation.equals(Long.toString(binding.conversationId()))
     ||binding.assignmentRevision()!=current.assignmentRevision()||locked==null||locked.getId()==null
     ||!conversation.equals(Long.toString(locked.getId()))
     ||!Objects.equals(observed.getLifecycleGeneration(),locked.getLifecycleGeneration())
     ||!Objects.equals(observed.getTaskId(),locked.getTaskId())
     ||!tenant.equals(locked.getTenantId())||!sender.jiacn().equals(locked.getJiacn())
     ||!sender.clientId().equals(locked.getClientId())
     ||!"juyiting".equals(locked.getConversationType())||!"bounty".equals(locked.getConversationScopeType())
     ||!("task:"+current.taskId()).equals(locked.getConversationScopeKey())
     ||!List.of(target).equals(scopes.parsePersistedTargetAgentIds(locked.getTargetAgentIds()))
     ||locked.getDeletedAt()!=null)throw conflict();
   return new ChatBountyInteractionV3Wire.ContextData(1,conversation,
     Long.toString(locked.getLifecycleGeneration()),current.taskId(),current.targetAgentId(),
     Long.toString(current.taskVersion()),Long.toString(current.assignmentRevision()),
     Long.toString(current.baselineGrantVersion()),Long.toString(current.requirementRevision()));
  } catch(ChatDeliberationException typed) { throw typed; }
  catch(AgentTaskExecutionGrantException grant) {
   if(grant.status()==404)throw notFound();
   if(grant.status()==409||grant.status()==422||grant.status()==403)throw conflict();
   if(grant.status()==400)throw new ChatDeliberationException(
     ChatDeliberationException.Reason.INVALID_REQUEST,"Invalid schema-3 bounty follow-up",grant);
   throw unavailable(grant);
  } catch(DataAccessException|TransactionException storage) { throw unavailable(storage); }
  catch(IllegalStateException drift) { throw conflict(drift); }
 }

 @Transactional(readOnly=true,rollbackFor=Exception.class)
 public Prepared prepare(String tenant,ServerResolvedSender sender,String conversation,String interactionKey,ChatBountyInteractionV3Wire.IntentValue intent,boolean lockSource){
  if(!"0".equals(tenant)||sender==null||!"user".equals(sender.type()))throw notFound();ChatConversationEntity c=lockSource?conversations.lockScopedById(sender.jiacn(),sender.clientId(),conversation):conversations.findScopedById(sender.jiacn(),sender.clientId(),conversation);requireConversation(c,tenant,sender,conversation,intent);List<String> targets=scopes.parsePersistedTargetAgentIds(c.getTargetAgentIds());if(!targets.equals(List.of(intent.targetAgentId())))throw conflict();
  var scope=new AgentTaskExecutionGrantService.Scope(tenant,sender.clientId(),sender.jiacn());var base=grants.resolveFollowupBaseline(scope,intent.taskId(),intent.grantVersion(),intent.taskVersion(),intent.assignmentRevision(),intent.requirementRevision(),intent.targetAgentId());
  List<ChatConversationAssetSourceResolver.Ref> refs=intent.inputRefs().stream().map(r->new ChatConversationAssetSourceResolver.Ref(r.kind(),r.fileId(),r.version(),r.purpose(),r.assetRef()==null?null:r.assetRef().assetId(),r.assetRef()==null?null:r.assetRef().revision())).toList();var parent=intent.continuationOf()==null?null:new ChatConversationAssetSourceResolver.Parent(intent.continuationOf().requestId(),intent.continuationOf().stepId());var resolved=sources.resolve(tenant,sender.clientId(),sender.jiacn(),conversation,intent.conversationGeneration(),intent.taskId(),intent.targetAgentId(),intent.operation(),refs,parent,base.inputs(),lockSource);
  String requestId=stable("mmd-interaction-request",tenant,sender.clientId(),sender.jiacn(),interactionKey);String stepId=stable("mmd-interaction-step",requestId);String executionIntent=stable("mmd-interaction-execution",stepId);String ownerSha=ChatBountyInteractionV3Wire.sha(intent.canonicalOwner());String instructionSha=ChatBountyInteractionV3Wire.instructionSha(intent.content());List<Map<String,Object>> sourceDomain=resolved.stream().map(x->{Map<String,Object> item=new LinkedHashMap<>();item.put("byteLength",Long.toString(x.byteLength()));item.put("contentMimeType",x.contentMimeType());item.put("inputRef",x.inputRef());item.put("kind",x.kind());item.put("sha256",x.sha256());item.put("source",sourceDescriptor(x));return item;}).toList();String sourceSha=ChatBountyInteractionV3Wire.sha(Map.of("conversationGeneration",Long.toString(intent.conversationGeneration()),"conversationId",conversation,"operation",intent.operation(),"schemaVersion",1,"sources",sourceDomain,"taskId",intent.taskId()));
  var baseline=new ControlledImageFollowupAuthorityService.Baseline(base.grantId(),base.grantVersion(),Objects.requireNonNull(base.taskVersion()),base.assignmentRevision(),Objects.requireNonNull(base.requirementRevision()),base.requirementSha256(),base.targetAgentId());var command=new ControlledImageFollowupAuthorityService.PreviewCommand(intent.taskId(),conversation,intent.conversationGeneration(),interactionKey,requestId,stepId,executionIntent,baseline,intent.operation(),intent.content(),instructionSha,ownerSha,sourceSha,resolved);var preview=authority.preview(new ControlledImageFollowupAuthorityService.Scope(tenant,sender.clientId(),sender.jiacn()),command);return new Prepared(command,preview,ownerSha,instructionSha,sourceSha);
 }
 private static Map<String,Object> sourceDescriptor(ControlledImageFollowupAuthorityService.Source x){
  Map<String,Object> value=new LinkedHashMap<>();
  if("TASK_LINKED_WORKSPACE_VERSION".equals(x.kind())){value.put("fileId",x.fileId());value.put("kind",x.kind());value.put("purpose",x.purpose());value.put("version",Integer.toString(x.fileVersion()));}
  else{value.put("assetId",x.assetId());value.put("assetRevision",Long.toString(x.assetRevision()));value.put("conversationGeneration",Long.toString(x.conversationGeneration()));value.put("conversationId",x.conversationId());value.put("kind",x.kind());value.put("producerExecutionId",x.producerExecutionId());value.put("producerOutputId",x.producerOutputId());value.put("producerRequestId",x.producerRequestId());value.put("producerRunId",x.producerRunId());value.put("producerStepId",x.producerStepId());}
  return value;
 }
 private static void requireConversation(ChatConversationEntity c,String tenant,ServerResolvedSender sender,String id,ChatBountyInteractionV3Wire.IntentValue i){if(c==null||c.getId()==null||!id.equals(Long.toString(c.getId()))||!tenant.equals(c.getTenantId())||!sender.jiacn().equals(c.getJiacn())||!sender.clientId().equals(c.getClientId())||c.getDeletedAt()!=null||!"juyiting".equals(c.getConversationType())||!"bounty".equals(c.getConversationScopeType())||!i.taskId().equals(c.getTaskId())||!("task:"+i.taskId()).equals(c.getConversationScopeKey())||!Objects.equals(i.conversationGeneration(),c.getLifecycleGeneration()))throw notFound();}
 private static String stable(String prefix,String...fields){StringBuilder b=new StringBuilder(prefix);for(String f:fields)b.append('\n').append(f.length()).append(':').append(f);return ChatBountyInteractionV3Wire.shaText(b.toString());}
 private static ChatDeliberationException notFound(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Bounty follow-up is unavailable");}
 private static ChatDeliberationException conflict(){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Bounty follow-up changed");}
 private static ChatDeliberationException conflict(Throwable cause){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Bounty follow-up changed",cause);}
 private static ChatDeliberationException unavailable(Throwable cause){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"Bounty follow-up is unavailable",cause);}
}
