package cn.jia.chat.service;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.springframework.stereotype.Service;
import java.util.*;
/** Server-only exact source resolver. It never accepts browser lineage, MIME, hash or byte length. */
@Service
public class ChatConversationAssetSourceResolver implements ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup {
 private final ChatConversationArchiveStore archive; private final ChatInteractionStepStore steps;
 private final ChatConversationDao conversations; private final JuyitingConversationScopeService scopes;
 public ChatConversationAssetSourceResolver(ChatConversationArchiveStore archive,ChatInteractionStepStore steps,
   ChatConversationDao conversations,JuyitingConversationScopeService scopes){this.archive=Objects.requireNonNull(archive);this.steps=Objects.requireNonNull(steps);this.conversations=Objects.requireNonNull(conversations);this.scopes=Objects.requireNonNull(scopes);}
 public record Ref(String kind,String fileId,Integer version,String purpose,String assetId,Long assetRevision) { }
 public record Parent(String requestId,String stepId) { }
 public List<ControlledImageFollowupAuthorityService.Source> resolve(String tenant,String client,String owner,
   String conversation,long generation,String task,String targetAgent,String operation,List<Ref> refs,Parent parent,
   List<AgentTaskExecutionGrantService.AuthorizedInput> baseline,boolean lock){
  if(refs==null||baseline==null)throw conflict();List<ControlledImageFollowupAuthorityService.Source> out=new ArrayList<>();
  if("GENERATE_IMAGE".equals(operation)){if(refs.size()>16)throw invalid();if(parent!=null){var prior=lock?steps.findStepForUpdate(tenant,owner,client,parent.requestId(),1,1):steps.findStep(tenant,owner,client,parent.requestId(),1,1);if(prior==null||!parent.stepId().equals(prior.stepId())||!conversation.equals(prior.conversationId())||generation!=prior.conversationGeneration()||!task.equals(prior.taskId()))throw conflict();}Set<String> seen=new HashSet<>();int i=0;for(Ref ref:refs){if(ref==null||!"TASK_LINKED_WORKSPACE_VERSION".equals(ref.kind())||ref.fileId()==null||ref.version()==null||ref.version()<1||!"REFERENCE".equals(ref.purpose())||!seen.add(ref.fileId()+"\n"+ref.version()))throw invalid();var exact=baseline.stream().filter(v->ref.fileId().equals(v.fileId())&&ref.version()==v.version()&&"REFERENCE".equals(v.purpose())).findFirst().orElseThrow(ChatConversationAssetSourceResolver::conflict);if(exact.byteLength()<1||!List.of("image/jpeg","image/png").contains(exact.contentMimeType()))throw conflict();String sourceJson=CanonicalContextJson.write(Map.of("fileId",exact.fileId(),"kind",ref.kind(),"purpose","REFERENCE","version",Integer.toString(exact.version())));out.add(new ControlledImageFollowupAuthorityService.Source("input_"+(++i),ref.kind(),exact.fileId(),exact.version(),"REFERENCE",null,null,null,null,null,null,null,null,null,null,exact.contentMimeType(),exact.byteLength(),exact.contentHash(),sourceJson));}return List.copyOf(out);}
  if(!"EDIT_IMAGE".equals(operation)||refs.size()!=1||parent==null)throw invalid();Ref ref=refs.getFirst();if(ref==null||!"CURRENT_CONVERSATION_ASSET".equals(ref.kind())||ref.assetId()==null||ref.assetRevision()==null||ref.assetRevision()<1)throw invalid();var scope=new ChatConversationArchiveStore.Scope(tenant,owner,client);var source=lock?archive.findAuthorizedSourceForUpdate(scope,conversation,ref.assetId(),ref.assetRevision(),targetAgent):archive.findAuthorizedSource(scope,conversation,ref.assetId(),ref.assetRevision(),targetAgent);if(source==null||source.conversationGeneration()!=generation||!task.equals(source.taskId())||!parent.requestId().equals(source.requestId())||!parent.stepId().equals(source.stepId())||source.byteLength()<1||!List.of("image/jpeg","image/png").contains(source.contentMimeType()))throw conflict();String sourceJson=CanonicalContextJson.write(Map.of("assetId",source.assetId(),"assetRevision",Long.toString(source.assetRevision()),"conversationGeneration",Long.toString(source.conversationGeneration()),"conversationId",source.conversationId(),"kind","CURRENT_CONVERSATION_ASSET","producerExecutionId",source.executionId(),"producerOutputId",source.outputId(),"producerRequestId",source.requestId(),"producerRunId",source.runId(),"producerStepId",source.stepId()));return List.of(new ControlledImageFollowupAuthorityService.Source("input_1","CURRENT_CONVERSATION_ASSET",null,null,null,source.conversationId(),source.conversationGeneration(),source.assetId(),source.assetRevision(),source.requestId(),source.requestRevision(),source.stepId(),source.executionId(),source.runId(),source.outputId(),source.contentMimeType(),source.byteLength(),source.sha256(),sourceJson));
 }
 @Override public void verify(ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup.SourceAccessScope scope,
   List<ControlledImageFollowupAuthorityService.Source> sources,boolean lock){
  if(scope==null||sources==null)throw conflict();
  ChatConversationEntity conversation=lock?conversations.lockScopedById(scope.ownerJiacn(),scope.clientId(),scope.conversationId()):conversations.findScopedById(scope.ownerJiacn(),scope.clientId(),scope.conversationId());
  if(conversation==null||conversation.getId()==null||!scope.conversationId().equals(Long.toString(conversation.getId()))
    ||!scope.tenantId().equals(conversation.getTenantId())||!scope.ownerJiacn().equals(conversation.getJiacn())
    ||!scope.clientId().equals(conversation.getClientId())||conversation.getDeletedAt()!=null
    ||!"juyiting".equals(conversation.getConversationType())||!"bounty".equals(conversation.getConversationScopeType())
    ||!scope.taskId().equals(conversation.getTaskId())||!("task:"+scope.taskId()).equals(conversation.getConversationScopeKey())
    ||!Objects.equals(scope.conversationGeneration(),conversation.getLifecycleGeneration())
    ||!List.of(scope.targetAgentId()).equals(scopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds())))throw conflict();
  var archiveScope=new ChatConversationArchiveStore.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId());
  for(var expected:sources){if(!"CURRENT_CONVERSATION_ASSET".equals(expected.kind()))continue;var actual=lock?archive.findAuthorizedSourceForUpdate(archiveScope,scope.conversationId(),expected.assetId(),expected.assetRevision(),scope.targetAgentId()):archive.findAuthorizedSource(archiveScope,scope.conversationId(),expected.assetId(),expected.assetRevision(),scope.targetAgentId());
   if(actual==null||!"CURRENT_CONVERSATION_ASSET".equals(expected.kind())||!Objects.equals(expected.assetId(),actual.assetId())||!Objects.equals(expected.assetRevision(),actual.assetRevision())||!scope.taskId().equals(actual.taskId())||!scope.conversationId().equals(actual.conversationId())||scope.conversationGeneration()!=actual.conversationGeneration()||!Objects.equals(expected.producerRequestId(),actual.requestId())||!Objects.equals(expected.producerRequestRevision(),actual.requestRevision())||!Objects.equals(expected.producerStepId(),actual.stepId())||!Objects.equals(expected.producerExecutionId(),actual.executionId())||!Objects.equals(expected.producerRunId(),actual.runId())||!Objects.equals(expected.producerOutputId(),actual.outputId())||!Objects.equals(expected.contentMimeType(),actual.contentMimeType())||!Objects.equals(expected.sha256(),actual.sha256())||expected.byteLength()!=actual.byteLength())throw conflict();
  }
 }
 private static ChatDeliberationException invalid(){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,"Invalid schema-3 source");}
 private static ChatDeliberationException conflict(){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"Schema-3 source changed");}
}
