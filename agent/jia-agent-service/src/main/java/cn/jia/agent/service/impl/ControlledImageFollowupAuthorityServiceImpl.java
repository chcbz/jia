package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import jakarta.inject.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static cn.jia.agent.service.ControlledImageFollowupAuthorityService.Reason;

/** One-way aggregate: task root -> baseline -> per-intent authority -> execution -> caller late Chat check. */
@Named
public class ControlledImageFollowupAuthorityServiceImpl implements ControlledImageFollowupAuthorityService {
 private static final String ACK="UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT";
 private static final long SAFE=9_007_199_254_740_991L;
 private final AgentTaskMutationTransaction tx; private final AgentTaskExecutionGrantService grants;
 private final AgentTaskProviderCostConsentDao consents; private final ControlledImageIntentOperationGrantDao opgrants;
 private final PersonalWorkspaceExecutionDao executions; private final ControlledImageExecutionSourceV3Dao sources;
 private final ControlledImageProviderOperatorPolicy policies; private final ObjectProvider<RuntimeDeclarationLookup> lookups;
 private final ObjectProvider<RuntimeSourceAccessLookup> sourceAccess;
 private ControlledImageBridgeOperationDao initialOperations;
 private final ObjectMapper json;
 @Inject public ControlledImageFollowupAuthorityServiceImpl(AgentTaskMutationTransaction tx,
   AgentTaskExecutionGrantService grants,AgentTaskProviderCostConsentDao consents,
   ControlledImageIntentOperationGrantDao opgrants,PersonalWorkspaceExecutionDao executions,
   ControlledImageExecutionSourceV3Dao sources,ControlledImageProviderOperatorPolicy policies,
   ObjectProvider<RuntimeDeclarationLookup> lookups,ObjectProvider<RuntimeSourceAccessLookup> sourceAccess,ObjectMapper json){this.tx=Objects.requireNonNull(tx);this.grants=Objects.requireNonNull(grants);this.consents=Objects.requireNonNull(consents);this.opgrants=Objects.requireNonNull(opgrants);this.executions=Objects.requireNonNull(executions);this.sources=Objects.requireNonNull(sources);this.policies=Objects.requireNonNull(policies);this.lookups=Objects.requireNonNull(lookups);this.sourceAccess=Objects.requireNonNull(sourceAccess);this.json=Objects.requireNonNull(json);}
 @Autowired(required=false) public void setInitialControlledImageV3(ControlledImageBridgeOperationDao operations){this.initialOperations=operations;}

 @Override @Transactional(readOnly=true,rollbackFor=Exception.class)
 public Preview preview(Scope scope,PreviewCommand command){validate(scope,command);try{
  var base=baseline(scope,command); requireBaseline(command,base); return currentPreview(scope,command);
 }catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}

 @Override public ControlledImageFollowupAuthorityDTO issue(Scope scope,IssueCommand command,LateCheck late){
  if(command==null||command.preview()==null||late==null)throw fail(Reason.BAD_REQUEST);
  validate(scope,command.preview()); id(command.issueIdempotencyKey(),100); hash(command.issueRequestDigest());
  if(!ACK.equals(command.acknowledgement())||command.expectedPreview()==null||command.requestedProvider()==null)throw fail(Reason.BAD_REQUEST);
  try{return tx.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),command.preview().taskId(),root->{
   var replay=opgrants.lockByIssueKey(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),command.preview().taskId(),command.issueIdempotencyKey());
   if(replay!=null){if(!same(replay.getIssueRequestDigest(),command.issueRequestDigest()))throw fail(Reason.CONFLICT);return view(scope,replay,true);}
   var base=baseline(scope,command.preview());requireBaseline(command.preview(),base);
   Preview current=currentPreview(scope,command.preview());requireExpected(command,current);
   String consentId="consent_"+UUID.randomUUID().toString().replace("-","");
   String opId="opgrant_"+UUID.randomUUID().toString().replace("-",""); long now=System.currentTimeMillis();
   var policy=policies.requireCurrent(consentScope(scope),command.preview().baseline().targetAgentId(),current.provider().bindingId(),current.provider().bindingEpoch(),now);
   if(!same(policy.bindingId(),current.provider().bindingId())||policy.bindingEpoch()!=current.provider().bindingEpoch()
      ||!same(policy.modelId(),current.provider().modelId())||!same(policy.custody(),current.provider().custody())
      ||!same(policy.policyRevision(),current.provider().operatorPolicyRevision())||!same(policy.pricingMode(),current.pricingMode())
      ||policy.maxOutboundRequestAttempts()!=current.maxOutboundRequestAttempts()||policy.expiresAt()!=current.expiresAt())throw fail(Reason.CONFLICT);
   var consent=new AgentTaskProviderCostConsentEntity().setConsentId(consentId).setConsentPurpose("FOLLOWUP_EXECUTE")
    .setOperationGrantId(opId).setExecutionIntentId(command.preview().executionIntentId()).setConversationId(command.preview().conversationId())
    .setConversationGeneration(command.preview().conversationGeneration()).setOperation(command.preview().operation())
    .setInstructionSha256(command.preview().instructionSha256()).setSourceSnapshotSha256(command.preview().sourceSnapshotSha256())
    .setOwnerPayloadSha256(command.preview().ownerPayloadSha256()).setRuntimeInputSnapshotSha256(null);
   consent.setTenantId(scope.tenantId());consent.setClientId(scope.clientId());consent.setOwnerJiacn(scope.ownerJiacn())
    .setTaskId(command.preview().taskId()).setTargetAgentId(command.preview().baseline().targetAgentId())
    .setIdempotencyKey(command.issueIdempotencyKey()).setRequestDigest(command.issueRequestDigest())
    .setAssignmentIdempotencyKey(base.assignmentIdempotencyKey()).setAssignmentBaseHash(base.assignmentBaseHash())
    .setTaskVersion(command.preview().baseline().taskVersion()).setRequirementRevision(command.preview().baseline().requirementRevision())
    .setRequirementSha256(command.preview().baseline().requirementSha256()).setInputSnapshotDigest(command.preview().sourceSnapshotSha256())
    .setInputSnapshotJson(write(command.preview().sources())).setProviderLane(policy.providerLane()).setBindingId(policy.bindingId())
    .setBindingEpoch(policy.bindingEpoch()).setModelId(policy.modelId()).setCustody(policy.custody()).setOperatorIssuer(policy.issuer())
    .setOperatorPolicyRevision(policy.policyRevision()).setPricingMode(policy.pricingMode()).setMaxOutboundRequestAttempts(1)
    .setExpiresAt(policy.expiresAt()).setState("ISSUED").setVersion(1L).setCreatedAt(now);
   var op=operationRow(scope,command,base,opId,consentId,now);
   consents.insert(consent);opgrants.insert(op);late.verify();return view(scope,op,false);
  });}catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}

 @Override @Transactional(readOnly=true,rollbackFor=Exception.class)
 public ControlledImageFollowupAuthorityDTO getByIssueKey(Scope scope,String taskId,String conversationId,String key){
  validScope(scope);id(taskId,100);id(conversationId,100);id(key,100);try{var row=opgrants.findByIssueKey(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,key);if(row==null||!same(conversationId,row.getConversationId()))throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);return view(scope,row,true);}catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}

 @Override @Transactional(readOnly=true,rollbackFor=Exception.class)
 public ControlledImageFollowupAuthorityDTO reconcileIssue(Scope scope,String taskId,String conversationId,String key,String digest){
  try{var value=getByIssueKey(scope,taskId,conversationId,key);var row=opgrants.findByIssueKey(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,key);if(row==null||!same(digest,row.getIssueRequestDigest()))throw fail(Reason.CONFLICT);return value;}catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}
 @Override @Transactional(readOnly=true,rollbackFor=Exception.class)
 public ControlledImageFollowupAuthorityDTO getByInteractionKey(Scope scope,String taskId,String conversationId,String key){
  validScope(scope);id(taskId,100);id(conversationId,100);id(key,100);try{var row=opgrants.findByInteractionKey(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,key);if(row==null||!same(conversationId,row.getConversationId()))throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);return view(scope,row,true);}catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}

 @Override public ControlledImageFollowupAuthorityDTO revoke(Scope scope,RevokeCommand c){
  if(c==null)throw fail(Reason.BAD_REQUEST);validScope(scope);id(c.taskId(),100);id(c.conversationId(),100);id(c.consentId(),100);id(c.operationGrantId(),100);id(c.idempotencyKey(),100);hash(c.requestDigest());positive(c.expectedConsentVersion());positive(c.expectedOperationGrantVersion());
  try{return tx.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),root->{
   var op=opgrants.lockById(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),c.operationGrantId());var consent=consents.findFollowupByConsentForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),c.consentId());
   if(op==null||consent==null||!same(c.conversationId(),op.getConversationId())||!same(c.consentId(),op.getConsentId())||!same(c.operationGrantId(),consent.getOperationGrantId()))throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
   if("REVOKED".equals(op.getState())&&"REVOKED".equals(consent.getState())){if(same(c.idempotencyKey(),op.getRevokeIdempotencyKey())&&same(c.requestDigest(),op.getRevokeRequestDigest()))return view(scope,op,true);throw fail(Reason.CONFLICT);}
   if("CONSUMED".equals(op.getState())||"CONSUMED".equals(consent.getState())||!Objects.equals(c.expectedOperationGrantVersion(),op.getVersion())||!Objects.equals(c.expectedConsentVersion(),consent.getVersion()))throw fail(Reason.CONFLICT);
   long now=System.currentTimeMillis();op.setRevokeIdempotencyKey(c.idempotencyKey()).setRevokeRequestDigest(c.requestDigest()).setRevokedAt(now).setUpdateTime(now);consent.setRevokeIdempotencyKey(c.idempotencyKey()).setRevokeRequestDigest(c.requestDigest()).setRevokedAt(now).setUpdateTime(now);
   if(!opgrants.revoke(op,c.expectedOperationGrantVersion())||!consents.revokeFollowup(consent,c.expectedConsentVersion()))throw fail(Reason.CONFLICT);op.setState("REVOKED").setVersion(c.expectedOperationGrantVersion()+1);return view(scope,op,false);
  });}catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}

 @Override public Reservation reserve(Scope scope,ReserveCommand c,LateCheck late){
  if(c==null||c.preview()==null||c.authority()==null||late==null)throw fail(Reason.BAD_REQUEST);validate(scope,c.preview());hash(c.interactionRequestDigest());hash(c.runtimeInputSnapshotDigest());id(c.executionId(),100);id(c.runId(),100);if(!"image/png".equals(c.outputContentMimeType())||!sameHash(c.runtimeInputSnapshotDigest(),runtimeDigest(c.preview(),c.executionId(),c.runId())))throw fail(Reason.BAD_REQUEST);
  try{return tx.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.preview().taskId(),root->{
   var prior=executions.findByIdempotency(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),"conv_"+sha(c.preview().executionIntentId()));
   if(prior!=null){
    if(!same(prior.getRequestHash(),c.interactionRequestDigest())||!same(prior.getExecutionId(),c.executionId())
      ||!same(prior.getRunId(),c.runId())||!sameHash(prior.getRuntimeInputSnapshotDigest(),c.runtimeInputSnapshotDigest())
      ||!same(prior.getControlledConsentId(),c.authority().consentId())
      ||!same(prior.getOperationGrantId(),c.authority().operationGrantId()))throw fail(Reason.CONFLICT);
    var persistedOp=opgrants.findById(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.preview().taskId(),prior.getOperationGrantId());
    var persistedConsent=consents.findFollowupByConsent(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.preview().taskId(),prior.getControlledConsentId());
    if(persistedOp==null||persistedConsent==null||!reservedOrConsumed(persistedOp.getState())
      ||!reservedOrConsumed(persistedConsent.getState())
      ||!same(prior.getExecutionId(),persistedOp.getReservedExecutionId())
      ||!same(prior.getRunId(),persistedOp.getReservedRunId()))throw fail(Reason.CONFLICT);
    return new Reservation(prior.getExecutionId(),prior.getRunId(),persistedConsent.getConsentId(),
      persistedOp.getOperationGrantId(),persistedConsent.getVersion(),persistedOp.getVersion(),
      prior.getRuntimeInputSnapshotDigest());
   }
   var base=baseline(scope,c.preview());requireBaseline(c.preview(),base);
   var op=opgrants.lockById(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.preview().taskId(),c.authority().operationGrantId());
   var consent=consents.findFollowupByConsentForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.preview().taskId(),c.authority().consentId());
   requireAuthority(c,op,consent);
   requireCurrentProvider(scope,c.preview(),consent);
   long now=System.currentTimeMillis();var row=new PersonalWorkspaceExecutionEntity().setExecutionId(c.executionId()).setOwnerJiacn(scope.ownerJiacn()).setTaskId(c.preview().taskId()).setRunId(c.runId()).setExecutionMode("CONVERSATION").setTaskGrantId(c.preview().baseline().grantId()).setTaskGrantVersion(c.preview().baseline().grantVersion()).setAssignmentRevision(c.preview().baseline().assignmentRevision()).setPermittedOperation(c.preview().operation()).setControlledConsentId(c.authority().consentId()).setExecutionProtocolVersion(3).setOperationGrantId(c.authority().operationGrantId()).setRuntimeInputSnapshotDigest(c.runtimeInputSnapshotDigest()).setConversationId(c.preview().conversationId()).setTargetAgentId(c.preview().baseline().targetAgentId()).setInstruction(c.preview().instruction()).setOutputContentMimeType("image/png").setExecutionState("QUEUED").setGrantRevision(1L).setIdempotencyKey("conv_"+sha(c.preview().executionIntentId())).setRequestHash(c.interactionRequestDigest()).setCreatedAt(now);
   row.setTenantId(scope.tenantId());row.setClientId(scope.clientId());executions.insert(row);
   int n=0;for(Source source:c.preview().sources())sources.insert(sourceRow(scope,row.getExecutionId(),++n,source,now));
   op.setReservedExecutionId(row.getExecutionId()).setReservedRunId(row.getRunId()).setUpdateTime(now);if(!opgrants.reserve(op,c.authority().operationGrantVersion()))throw fail(Reason.CONFLICT);
   consent.setBoundGrantId(c.preview().baseline().grantId()).setBoundGrantVersion(c.preview().baseline().grantVersion()).setBoundAssignmentRevision(c.preview().baseline().assignmentRevision()).setUpdateTime(now);if(!consents.bindFollowup(consent,c.authority().consentVersion()))throw fail(Reason.CONFLICT);
   consent.setReservedExecutionId(row.getExecutionId()).setReservedRunId(row.getRunId()).setRuntimeInputSnapshotSha256(c.runtimeInputSnapshotDigest()).setUpdateTime(now);if(!consents.reserveFollowup(consent,c.authority().consentVersion()+1))throw fail(Reason.CONFLICT);
   late.verify();return new Reservation(row.getExecutionId(),row.getRunId(),c.authority().consentId(),c.authority().operationGrantId(),c.authority().consentVersion()+2,c.authority().operationGrantVersion()+1,c.runtimeInputSnapshotDigest());
  });}catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}

 @Override @Transactional(rollbackFor=Exception.class)
 public RuntimeAuthority runtimeAuthority(RuntimeScope scope,String taskId,String runId,String purpose){
  if(scope==null||!Set.of("COMMAND","INPUTS","EXISTING_RUN","RESULT","RESULT_RECOVERY","FAILURE").contains(purpose))throw fail(Reason.BAD_REQUEST);
  Scope owner=new Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());validScope(owner);id(scope.targetAgentId(),100);id(scope.runtimeInstanceId(),100);id(taskId,100);id(runId,100);
  try{var execution=executions.findByTaskRun(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,runId);
   if(execution==null||!Objects.equals(3,execution.getExecutionProtocolVersion())||!same(scope.targetAgentId(),execution.getTargetAgentId()))throw fail(Reason.NOT_FOUND_OR_FORBIDDEN);
   var op=opgrants.findById(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,execution.getOperationGrantId());
   if(op==null)return initialRuntimeAuthority(scope,execution,purpose);
   if(!same(execution.getOperationGrantId(),op.getOperationGrantId())||!same(execution.getExecutionId(),op.getReservedExecutionId())||!same(runId,op.getReservedRunId()))throw fail(Reason.CONFLICT);
   var consent=consents.findFollowupByConsent(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,execution.getControlledConsentId());
   boolean failure="FAILURE".equals(purpose);
   boolean providerStarted=execution.getConversationProviderStartedAt()!=null
     ||execution.getConversationProviderLeaseVersion()!=null;
   if("RESULT".equals(purpose)||"RESULT_RECOVERY".equals(purpose)||failure&&providerStarted) {
    // Provider START already consumed the only callable authority. Result delivery proves that
    // exact persisted lease and deliberately does not reacquire declaration, policy or source access.
    requireConsumedResultAuthority(scope,execution,op,consent,"RESULT_RECOVERY".equals(purpose));
    return runtimeAuthority(execution,persistedProvider(consent));
   }
   boolean state=consent!=null && ("EXISTING_RUN".equals(purpose)
     ? reservedOrConsumed(op.getState()) && reservedOrConsumed(consent.getState())
     : "RESERVED".equals(op.getState()) && "RESERVED".equals(consent.getState()));
   if(!state||!same(op.getConsentId(),consent.getConsentId()))throw fail(Reason.CONFLICT);
   PreviewCommand preview=previewFrom(op,execution);
   RuntimeDeclarationLookup.Declaration currentDeclaration=null;
   if(!"EXISTING_RUN".equals(purpose)) {
    var base=baseline(owner,preview);requireBaseline(preview,base);
    if(!failure)currentDeclaration=requireCurrentProvider(owner,preview,consent);
   }
   // A pre-START failure may be reporting the exact source-read error; rechecking that source here
   // would turn an honest terminal report into a false 404. Root/grant/session/fence/Chat ACL remain.
   if(!failure)requireSourceAccess(owner,preview,false);
   ProviderExecution provider;
   if("EXISTING_RUN".equals(purpose)||failure) {
    var session=sessionDeclaration(owner,scope.targetAgentId(),execution.getPermittedOperation());
    if(!same(scope.runtimeInstanceId(),session.runtimeInstanceId())
      ||!session.operations().contains(execution.getPermittedOperation()))throw fail(Reason.CONFLICT);
    provider=persistedProvider(consent);
   } else {
    var d=Objects.requireNonNull(currentDeclaration);
    if(!same(scope.runtimeInstanceId(),d.runtimeInstanceId())||!d.operations().contains(execution.getPermittedOperation())
      ||!same(d.bindingId(),consent.getBindingId())||!Objects.equals(d.bindingEpoch(),consent.getBindingEpoch())
      ||!same(d.modelId(),consent.getModelId()))throw fail(Reason.CONFLICT);
    provider=new ProviderExecution(d.providerLane(),consent.getConsentId(),d.bindingId(),
      Long.toString(d.bindingEpoch()),d.modelId(),16,1,1);
   }
   return runtimeAuthority(execution,provider);
  }catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}

 @Override public StartReceipt consumeForStart(RuntimeScope scope,StartCommand c,LateCheck late){
  if(scope==null||c==null||late==null)throw fail(Reason.BAD_REQUEST);validScope(new Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()));id(scope.targetAgentId(),100);id(scope.runtimeInstanceId(),100);id(c.taskId(),100);id(c.runId(),100);id(c.executionId(),100);id(c.commandId(),100);id(c.messageId(),100);hash(c.inputSnapshotDigest());positive(c.leaseVersion());id(c.leaseId(),100);
  try{return tx.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),root->{
   var execution=executions.lockByTaskRun(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),c.runId());if(execution==null||!Objects.equals(3,execution.getExecutionProtocolVersion())||!same(c.executionId(),execution.getExecutionId())||!same(scope.targetAgentId(),execution.getTargetAgentId())||!same(c.operation(),execution.getPermittedOperation())||!same(c.inputSnapshotDigest(),execution.getRuntimeInputSnapshotDigest())||execution.getConversationProviderStartedAt()!=null)throw fail(Reason.CONFLICT);
   var op=opgrants.lockById(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),execution.getOperationGrantId());
   if(op==null)return consumeInitialForStart(scope,c,late,execution);
   var consent=consents.findFollowupByConsentForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),execution.getControlledConsentId());
   if(op==null||consent==null||!"RESERVED".equals(op.getState())||!"RESERVED".equals(consent.getState())||!same(execution.getExecutionId(),op.getReservedExecutionId())||!same(execution.getRunId(),op.getReservedRunId())||!same(execution.getExecutionId(),consent.getReservedExecutionId())||!same(c.inputSnapshotDigest(),consent.getRuntimeInputSnapshotSha256()))throw fail(Reason.CONFLICT);
   Scope owner=new Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
   PreviewCommand preview=previewFrom(op,execution);var base=baseline(owner,preview);requireBaseline(preview,base);
   var declaration=requireCurrentProvider(owner,preview,consent);if(!same(scope.runtimeInstanceId(),declaration.runtimeInstanceId())||!declaration.operations().contains(c.operation()))throw fail(Reason.CONFLICT);
   ProviderExecution expected=new ProviderExecution(declaration.providerLane(),consent.getConsentId(),declaration.bindingId(),Long.toString(declaration.bindingEpoch()),declaration.modelId(),16,1,1);if(!expected.equals(c.providerExecution()))throw fail(Reason.CONFLICT);
   // Re-entering the already-held Agent root/execution checks must happen before any Chat row is
   // locked. The subsequent source verifier takes the current Chat ACL/lineage lock, after which
   // this aggregate only performs the three terminal Agent CAS writes and never reacquires a root.
   late.verify();
   requireSourceAccess(owner,preview,true);
   long now=System.currentTimeMillis();op.setConsumedLeaseId(c.leaseId()).setUpdateTime(now);consent.setConsumedLeaseId(c.leaseId()).setConsumedAt(now).setUpdateTime(now);if(!opgrants.consume(op,op.getVersion())||!consents.consumeFollowup(consent,consent.getVersion())||!executions.markControlledProviderStartedV3(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),c.runId(),c.executionId(),consent.getConsentId(),op.getOperationGrantId(),c.leaseVersion(),now))throw fail(Reason.CONFLICT);
   return new StartReceipt(c.taskId(),c.runId(),execution.getConversationId(),c.executionId(),c.commandId(),c.messageId(),c.operation(),c.inputSnapshotDigest(),expected,c.leaseVersion());
  });}catch(Failure f){throw f;}catch(RuntimeException f){throw translate(f);}}


 private RuntimeAuthority initialRuntimeAuthority(RuntimeScope scope,PersonalWorkspaceExecutionEntity execution,String purpose){
  if(initialOperations==null||execution.getOperationGrantId()==null)throw fail(Reason.UNAVAILABLE);
  var bridge=initialOperations.findByOperationGrant(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),execution.getTaskId(),execution.getOperationGrantId());
  var consent=consents.findByConsent(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),execution.getTaskId(),execution.getControlledConsentId());
  requireInitialBridge(execution,bridge,consent);
  boolean providerStarted=execution.getConversationProviderStartedAt()!=null
    ||execution.getConversationProviderLeaseVersion()!=null;
  if("RESULT".equals(purpose)||"RESULT_RECOVERY".equals(purpose)||"FAILURE".equals(purpose)&&providerStarted) {
   requireConsumedResultAuthority(scope,execution,null,consent,"RESULT_RECOVERY".equals(purpose));
   return runtimeAuthority(execution,persistedProvider(consent));
  }
  String authorityPurpose="EXISTING_RUN".equals(purpose)?"EXISTING_RUN":"PROVIDER_START";
  var admitted=grants.admitControlledV3(new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
    execution.getTaskId(),execution.getTaskGrantId(),execution.getTaskGrantVersion(),execution.getAssignmentRevision(),
    execution.getTargetAgentId(),execution.getPermittedOperation(),authorityPurpose,execution.getExecutionId(),execution.getRunId(),
    "EXISTING_RUN".equals(purpose)?null:scope.runtimeInstanceId());
  if(admitted==null||!admitted.paidExecutionAuthorized()||!same(admitted.costAuthorizationRef(),"mmd-ci-v1:"+consent.getConsentId()))throw fail(Reason.CONFLICT);
  RuntimeDeclarationLookup.Declaration declaration;
  if("EXISTING_RUN".equals(purpose)) {
   var session=sessionDeclaration(new Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),scope.targetAgentId(),execution.getPermittedOperation());
   if(!same(scope.runtimeInstanceId(),session.runtimeInstanceId()))throw fail(Reason.CONFLICT);
   declaration=null;
  } else declaration=declaration(new Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),scope.targetAgentId());
  if(declaration!=null&&(!same(scope.runtimeInstanceId(),declaration.runtimeInstanceId())
    ||!same(declaration.bindingId(),consent.getBindingId())||!Objects.equals(declaration.bindingEpoch(),consent.getBindingEpoch())
    ||!same(declaration.modelId(),consent.getModelId())))throw fail(Reason.CONFLICT);
  return new RuntimeAuthority(execution.getExecutionId(),execution.getPermittedOperation(),execution.getRuntimeInputSnapshotDigest(),
    new ProviderExecution(consent.getProviderLane(),consent.getConsentId(),consent.getBindingId(),Long.toString(consent.getBindingEpoch()),consent.getModelId(),16,1,1));
 }
 private static void requireConsumedResultAuthority(RuntimeScope scope,
   PersonalWorkspaceExecutionEntity execution,ControlledImageIntentOperationGrantEntity op,
   AgentTaskProviderCostConsentEntity consent,boolean recovery){
  Long leaseVersion=execution.getConversationProviderLeaseVersion();
  String originalRuntime=execution.getConversationLeaseRuntimeId();
  // Authentication independently proves the current live registered runtime. Recovery validates
  // the historical consumed START against its original runtime, never rewrites it for a new caller.
  if(originalRuntime==null||originalRuntime.isBlank()
    ||recovery&&(!Set.of("QUEUED","OUTPUT_COMMITTED").contains(
      Objects.requireNonNullElse(execution.getExecutionState(),""))
      ||execution.getConversationLeaseExpiresAt()==null)
    ||!recovery&&!same(scope.runtimeInstanceId(),originalRuntime)
    ||recovery&&!"OUTPUT_COMMITTED".equals(execution.getExecutionState())
      &&execution.getConversationLeaseExpiresAt()!=null
      &&execution.getConversationLeaseExpiresAt()>System.currentTimeMillis()
      &&!same(scope.runtimeInstanceId(),originalRuntime))throw fail(Reason.CONFLICT);
  if(consent==null||!"CONSUMED".equals(consent.getState())||consent.getConsumedAt()==null
    ||!same(execution.getControlledConsentId(),consent.getConsentId())
    ||!same(execution.getExecutionId(),consent.getReservedExecutionId())
    ||!same(execution.getRunId(),consent.getReservedRunId())
    ||!same(execution.getTargetAgentId(),consent.getTargetAgentId())
    ||consent.getOperation()!=null&&!same(execution.getPermittedOperation(),consent.getOperation())
    ||execution.getConversationProviderStartedAt()==null||execution.getConversationProviderStartedAt()<1
    ||leaseVersion==null||leaseVersion<1||leaseVersion>SAFE
    ||!Objects.equals(execution.getConversationLeaseVersion(),leaseVersion)
    ||consent.getRuntimeInputSnapshotSha256()!=null
      &&!sameHash(consent.getRuntimeInputSnapshotSha256(),execution.getRuntimeInputSnapshotDigest()))
   throw fail(Reason.CONFLICT);
  String leaseId=resultLeaseId(execution,originalRuntime,leaseVersion);
  if(!same(leaseId,consent.getConsumedLeaseId()))throw fail(Reason.CONFLICT);
  if(op!=null&&(!"CONSUMED".equals(op.getState())
    ||!same(execution.getOperationGrantId(),op.getOperationGrantId())
    ||!same(execution.getControlledConsentId(),op.getConsentId())
    ||!same(op.getOperationGrantId(),consent.getOperationGrantId())
    ||!same(execution.getExecutionId(),op.getReservedExecutionId())
    ||!same(execution.getRunId(),op.getReservedRunId())
    ||!same(execution.getTargetAgentId(),op.getTargetAgentId())
    ||!same(execution.getPermittedOperation(),op.getOperation())
    ||!same(leaseId,op.getConsumedLeaseId())))throw fail(Reason.CONFLICT);
 }
 private static String resultLeaseId(PersonalWorkspaceExecutionEntity execution,String runtimeInstanceId,long leaseVersion){
  return "pwe_lease_"+sha("controlled-provider-start-v3\n"+execution.getExecutionId()+"\n"+runtimeInstanceId+"\n"+leaseVersion);
 }
 private static ProviderExecution persistedProvider(AgentTaskProviderCostConsentEntity consent){
  if(consent==null||!"CONTROLLED_IMAGE_HTTP_V1".equals(consent.getProviderLane())
    ||consent.getConsentId()==null||consent.getBindingId()==null||consent.getBindingId().isBlank()
    ||consent.getBindingEpoch()==null||consent.getBindingEpoch()<1||consent.getBindingEpoch()>SAFE
    ||consent.getModelId()==null||consent.getModelId().isBlank()
    ||!Objects.equals(1,consent.getMaxOutboundRequestAttempts()))throw fail(Reason.CONFLICT);
  return new ProviderExecution(consent.getProviderLane(),consent.getConsentId(),consent.getBindingId(),
    Long.toString(consent.getBindingEpoch()),consent.getModelId(),16,1,1);
 }
 private static RuntimeAuthority runtimeAuthority(PersonalWorkspaceExecutionEntity execution,
   ProviderExecution provider){
  return new RuntimeAuthority(execution.getExecutionId(),execution.getPermittedOperation(),
    execution.getRuntimeInputSnapshotDigest(),provider);
 }

 private StartReceipt consumeInitialForStart(RuntimeScope scope,StartCommand c,LateCheck late,PersonalWorkspaceExecutionEntity execution){
  if(initialOperations==null||execution.getOperationGrantId()==null)throw fail(Reason.UNAVAILABLE);
  var bridge=initialOperations.lockByOperationGrant(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),execution.getOperationGrantId());
  var consent=consents.findByConsentForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),execution.getControlledConsentId());
  requireInitialBridge(execution,bridge,consent);
  if(!"RESERVED".equals(consent.getState())||!same(execution.getExecutionId(),consent.getReservedExecutionId())
    ||!same(execution.getRunId(),consent.getReservedRunId()))throw fail(Reason.CONFLICT);
  var admitted=grants.admitControlledV3(new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
    c.taskId(),execution.getTaskGrantId(),execution.getTaskGrantVersion(),execution.getAssignmentRevision(),
    execution.getTargetAgentId(),execution.getPermittedOperation(),"PROVIDER_START",execution.getExecutionId(),execution.getRunId(),scope.runtimeInstanceId());
  if(admitted==null||!admitted.paidExecutionAuthorized()||!same(admitted.costAuthorizationRef(),"mmd-ci-v1:"+consent.getConsentId()))throw fail(Reason.CONFLICT);
  var d=declaration(new Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),scope.targetAgentId());
  ProviderExecution expected=new ProviderExecution(d.providerLane(),consent.getConsentId(),d.bindingId(),Long.toString(d.bindingEpoch()),d.modelId(),16,1,1);
  if(!same(scope.runtimeInstanceId(),d.runtimeInstanceId())||!expected.equals(c.providerExecution()))throw fail(Reason.CONFLICT);
  late.verify();long now=System.currentTimeMillis();consent.setConsumedLeaseId(c.leaseId()).setConsumedAt(now).setUpdateTime(now);
  if(!consents.consume(consent,consent.getVersion())||!executions.markControlledProviderStartedV3(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),c.taskId(),c.runId(),c.executionId(),consent.getConsentId(),bridge.getOperationGrantId(),c.leaseVersion(),now))throw fail(Reason.CONFLICT);
  return new StartReceipt(c.taskId(),c.runId(),execution.getConversationId(),c.executionId(),c.commandId(),c.messageId(),c.operation(),c.inputSnapshotDigest(),expected,c.leaseVersion());
 }
 private static void requireInitialBridge(PersonalWorkspaceExecutionEntity execution,ControlledImageBridgeOperationEntity bridge,AgentTaskProviderCostConsentEntity consent){
  if(bridge==null||consent==null||!Objects.equals(3,bridge.getExecutionProtocolVersion())
    ||bridge.getOperationGrantId()==null||!bridge.getOperationGrantId().matches("opgrant_[0-9a-f]{32}")
    ||!same(execution.getOperationGrantId(),bridge.getOperationGrantId())||!same(execution.getTaskId(),bridge.getTaskId())
    ||!same(execution.getControlledConsentId(),bridge.getConsentId())||!same(execution.getTaskGrantId(),bridge.getGrantId())
    ||!Objects.equals(execution.getTaskGrantVersion(),bridge.getGrantVersion())||!Objects.equals(execution.getAssignmentRevision(),bridge.getAssignmentRevision())
    ||consent.getConsentPurpose()!=null&&!"INITIAL_ASSIGN_AND_START".equals(consent.getConsentPurpose())
    ||consent.getOperationGrantId()!=null||!same(consent.getConsentId(),bridge.getConsentId())
    ||!same(consent.getBoundGrantId(),bridge.getGrantId())||!Objects.equals(consent.getBoundGrantVersion(),bridge.getGrantVersion())
    ||!Objects.equals(consent.getBoundAssignmentRevision(),bridge.getAssignmentRevision()))throw fail(Reason.CONFLICT);
 }

 private void requireSourceAccess(Scope scope,PreviewCommand preview,boolean lock){
  var lookup=sourceAccess.getIfUnique();if(lookup==null)throw fail(Reason.UNAVAILABLE);
  try{lookup.verify(new RuntimeSourceAccessLookup.SourceAccessScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),preview.taskId(),preview.conversationId(),preview.conversationGeneration(),preview.baseline().targetAgentId()),preview.sources(),lock);}
  catch(Failure f){throw f;}
  catch(DataAccessException|TransactionException unavailable){throw fail(Reason.UNAVAILABLE,unavailable);}
  catch(RuntimeException denied){throw fail(Reason.CONFLICT,denied);}
 }
 private PreviewCommand previewFrom(ControlledImageIntentOperationGrantEntity o,PersonalWorkspaceExecutionEntity e){return new PreviewCommand(o.getTaskId(),o.getConversationId(),o.getConversationGeneration(),o.getInteractionIdempotencyKey(),o.getRequestId(),o.getStepId(),o.getExecutionIntentId(),new Baseline(o.getBaselineGrantId(),o.getBaselineGrantVersion(),o.getTaskVersion(),o.getAssignmentRevision(),o.getRequirementRevision(),o.getRequirementSha256(),o.getTargetAgentId()),o.getOperation(),e.getInstruction(),o.getInstructionSha256(),o.getOwnerPayloadSha256(),o.getSourceSnapshotSha256(),readAuthoritySources(o.getSourceSnapshotJson()));}
 private List<Source> readAuthoritySources(String raw){try{return json.readValue(raw,json.getTypeFactory().constructCollectionType(List.class,Source.class));}catch(Exception e){throw fail(Reason.UNAVAILABLE,e);}}
 private AgentTaskExecutionGrantService.Admission baseline(Scope s,PreviewCommand c){return grants.admitFollowupBaseline(new AgentTaskExecutionGrantService.Scope(s.tenantId(),s.clientId(),s.ownerJiacn()),c.taskId(),c.baseline().grantId(),c.baseline().grantVersion(),c.baseline().taskVersion(),c.baseline().assignmentRevision(),c.baseline().requirementRevision(),c.baseline().targetAgentId());}
 private static void requireBaseline(PreviewCommand c,AgentTaskExecutionGrantService.Admission a){if(a==null||!same(c.baseline().grantId(),a.grantId())||c.baseline().grantVersion()!=a.grantVersion()||!Objects.equals(c.baseline().taskVersion(),a.taskVersion())||c.baseline().assignmentRevision()!=a.assignmentRevision()||!Objects.equals(c.baseline().requirementRevision(),a.requirementRevision())||!same(c.baseline().requirementSha256(),a.requirementSha256())||!same(c.baseline().targetAgentId(),a.targetAgentId()))throw fail(Reason.CONFLICT);if("EDIT_IMAGE".equals(c.operation())&&a.inputs()==null)throw fail(Reason.CONFLICT);}
 private Preview currentPreview(Scope s,PreviewCommand c){var d=declaration(s,c.baseline().targetAgentId());if(!d.operations().contains(c.operation()))throw fail(Reason.UNAVAILABLE);long now=System.currentTimeMillis();var p=policies.requireCurrent(consentScope(s),c.baseline().targetAgentId(),d.bindingId(),d.bindingEpoch(),now);if(!same(p.modelId(),d.modelId()))throw fail(Reason.CONFLICT);return new Preview(d.runtimeInstanceId(),new ProviderExpectation(p.bindingId(),p.bindingEpoch(),p.modelId(),p.custody(),p.policyRevision()),p.pricingMode(),1,p.expiresAt());}
 private RuntimeDeclarationLookup.Declaration requireCurrentProvider(Scope s,PreviewCommand c,AgentTaskProviderCostConsentEntity consent){
  var d=declaration(s,c.baseline().targetAgentId());if(!d.operations().contains(c.operation()))throw fail(Reason.UNAVAILABLE);
  if(consent==null||consent.getBindingEpoch()==null)throw fail(Reason.CONFLICT);
  var p=policies.requireCurrent(consentScope(s),c.baseline().targetAgentId(),consent.getBindingId(),consent.getBindingEpoch(),System.currentTimeMillis());
  if(!same(d.providerLane(),consent.getProviderLane())||!same(d.bindingId(),consent.getBindingId())
    ||!Objects.equals(d.bindingEpoch(),consent.getBindingEpoch())||!same(d.modelId(),consent.getModelId())
    ||!same(p.providerLane(),consent.getProviderLane())||!same(p.bindingId(),consent.getBindingId())
    ||p.bindingEpoch()!=consent.getBindingEpoch()||!same(p.modelId(),consent.getModelId())
    ||!same(p.custody(),consent.getCustody())||!same(p.issuer(),consent.getOperatorIssuer())
    ||!same(p.policyRevision(),consent.getOperatorPolicyRevision())||!same(p.pricingMode(),consent.getPricingMode())
    ||!Objects.equals(p.maxOutboundRequestAttempts(),consent.getMaxOutboundRequestAttempts())
    ||!Objects.equals(p.expiresAt(),consent.getExpiresAt()))throw fail(Reason.CONFLICT);
  return d;
 }
 private RuntimeDeclarationLookup.Declaration declaration(Scope s,String target){var lookup=lookups.getIfUnique();if(lookup==null)throw fail(Reason.UNAVAILABLE);var d=lookup.current(new RuntimeDeclarationLookup.DeclarationScope(s.tenantId(),s.clientId(),s.ownerJiacn(),target));if(d==null||d.state()!=RuntimeDeclarationLookup.State.READY||d.runtimeInstanceId()==null||!Set.copyOf(d.operations()).equals(Set.of("GENERATE_IMAGE","EDIT_IMAGE"))||!"CONTROLLED_IMAGE_HTTP_V1".equals(d.providerLane())||d.bindingEpoch()==null||d.bindingEpoch()<1||d.maxInputItems()==null||d.maxInputItems()!=16||d.maxOutboundRequestAttempts()==null||d.maxOutboundRequestAttempts()!=1||d.precallFenceVersion()==null||d.precallFenceVersion()!=1)throw fail(Reason.UNAVAILABLE);return d;}
 private RuntimeDeclarationLookup.SessionDeclaration sessionDeclaration(Scope s,String target,String operation){var lookup=lookups.getIfUnique();if(lookup==null)throw fail(Reason.UNAVAILABLE);var d=lookup.currentSession(new RuntimeDeclarationLookup.DeclarationScope(s.tenantId(),s.clientId(),s.ownerJiacn(),target));if(d==null||d.state()!=RuntimeDeclarationLookup.State.READY||d.runtimeInstanceId()==null||!Set.copyOf(d.operations()).equals(Set.of("GENERATE_IMAGE","EDIT_IMAGE"))||!d.operations().contains(operation))throw fail(Reason.UNAVAILABLE);return d;}
 private static void requireExpected(IssueCommand c,Preview p){var e=c.expectedPreview();var requested=c.requestedProvider();var x=c.preview();if(!sameHash(e.ownerPayloadSha256(),x.ownerPayloadSha256())||!sameHash(e.instructionSha256(),x.instructionSha256())||!sameHash(e.sourceSnapshotSha256(),x.sourceSnapshotSha256())||!same(e.modelId(),p.provider().modelId())||!same(e.custody(),p.provider().custody())||!same(e.operatorPolicyRevision(),p.provider().operatorPolicyRevision())||!same(requested.bindingId(),p.provider().bindingId())||requested.bindingEpoch()!=p.provider().bindingEpoch()||!same(requested.modelId(),p.provider().modelId())||!same(requested.custody(),p.provider().custody())||!same(requested.operatorPolicyRevision(),p.provider().operatorPolicyRevision()))throw fail(Reason.CONFLICT);}
 private ControlledImageIntentOperationGrantEntity operationRow(Scope s,IssueCommand c,AgentTaskExecutionGrantService.Admission b,String opId,String consentId,long now){var x=c.preview();var r=new ControlledImageIntentOperationGrantEntity().setOperationGrantId(opId).setOwnerJiacn(s.ownerJiacn()).setTaskId(x.taskId()).setTargetAgentId(x.baseline().targetAgentId()).setConversationId(x.conversationId()).setConversationGeneration(x.conversationGeneration()).setInteractionIdempotencyKey(x.interactionIdempotencyKey()).setRequestId(x.requestId()).setStepId(x.stepId()).setExecutionIntentId(x.executionIntentId()).setBaselineGrantId(x.baseline().grantId()).setBaselineGrantVersion(x.baseline().grantVersion()).setTaskVersion(x.baseline().taskVersion()).setAssignmentRevision(x.baseline().assignmentRevision()).setRequirementRevision(x.baseline().requirementRevision()).setRequirementSha256(x.baseline().requirementSha256()).setOperation(x.operation()).setInstructionSha256(x.instructionSha256()).setSourceSnapshotSha256(x.sourceSnapshotSha256()).setSourceSnapshotJson(write(x.sources())).setOwnerPayloadSha256(x.ownerPayloadSha256()).setConsentId(consentId).setIssueIdempotencyKey(c.issueIdempotencyKey()).setIssueRequestDigest(c.issueRequestDigest()).setState("AUTHORIZED").setVersion(1L).setCreatedAt(now);r.setTenantId(s.tenantId());r.setClientId(s.clientId());return r;}
 private void requireAuthority(ReserveCommand c,ControlledImageIntentOperationGrantEntity o,AgentTaskProviderCostConsentEntity consent){var x=c.preview();
  if(o==null||consent==null||!"AUTHORIZED".equals(o.getState())||!"ISSUED".equals(consent.getState())
    ||!Objects.equals(c.authority().operationGrantVersion(),o.getVersion())||!Objects.equals(c.authority().consentVersion(),consent.getVersion())
    ||!same(c.authority().consentId(),o.getConsentId())||!same(c.authority().operationGrantId(),consent.getOperationGrantId())
    ||!same(x.taskId(),o.getTaskId())||!same(x.conversationId(),o.getConversationId())||!Objects.equals(x.conversationGeneration(),o.getConversationGeneration())
    ||!same(x.interactionIdempotencyKey(),o.getInteractionIdempotencyKey())||!same(x.requestId(),o.getRequestId())||!same(x.stepId(),o.getStepId())
    ||!same(x.executionIntentId(),o.getExecutionIntentId())||!same(x.baseline().grantId(),o.getBaselineGrantId())
    ||!Objects.equals(x.baseline().grantVersion(),o.getBaselineGrantVersion())||!Objects.equals(x.baseline().taskVersion(),o.getTaskVersion())
    ||!Objects.equals(x.baseline().assignmentRevision(),o.getAssignmentRevision())||!Objects.equals(x.baseline().requirementRevision(),o.getRequirementRevision())
    ||!sameHash(x.baseline().requirementSha256(),o.getRequirementSha256())||!same(x.baseline().targetAgentId(),o.getTargetAgentId())
    ||!sameHash(x.ownerPayloadSha256(),o.getOwnerPayloadSha256())||!sameHash(x.instructionSha256(),o.getInstructionSha256())
    ||!sameHash(x.sourceSnapshotSha256(),o.getSourceSnapshotSha256())||!same(x.operation(),o.getOperation())
    ||!"FOLLOWUP_EXECUTE".equals(consent.getConsentPurpose())||!same(o.getExecutionIntentId(),consent.getExecutionIntentId())
    ||!same(o.getConversationId(),consent.getConversationId())||!Objects.equals(o.getConversationGeneration(),consent.getConversationGeneration())
    ||!same(o.getOperation(),consent.getOperation())||!sameHash(o.getInstructionSha256(),consent.getInstructionSha256())
    ||!sameHash(o.getSourceSnapshotSha256(),consent.getSourceSnapshotSha256())||!sameHash(o.getOwnerPayloadSha256(),consent.getOwnerPayloadSha256())
    ||!same(o.getTaskId(),consent.getTaskId())||!same(o.getTargetAgentId(),consent.getTargetAgentId())
    ||!Objects.equals(o.getTaskVersion(),consent.getTaskVersion())
    ||!Objects.equals(o.getRequirementRevision(),consent.getRequirementRevision())
    ||!sameHash(o.getRequirementSha256(),consent.getRequirementSha256())
    ||!sameHash(x.sourceSnapshotSha256(),consent.getInputSnapshotDigest()))throw fail(Reason.CONFLICT);
 }

 private ControlledImageExecutionSourceV3Entity sourceRow(Scope s,String execution,int ordinal,Source x,long now){var r=new ControlledImageExecutionSourceV3Entity().setOwnerJiacn(s.ownerJiacn()).setExecutionId(execution).setInputRef("input_"+ordinal).setInputOrdinal(ordinal).setSourceKind(x.kind()).setContentMimeType(x.contentMimeType()).setByteLength(x.byteLength()).setContentSha256(x.sha256()).setSourceJson(x.sourceJson()).setFileId(x.fileId()).setFileVersion(x.fileVersion()).setPurpose(x.purpose()).setConversationId(x.conversationId()).setConversationGeneration(x.conversationGeneration()).setAssetId(x.assetId()).setAssetRevision(x.assetRevision()).setProducerRequestId(x.producerRequestId()).setProducerRequestRevision(x.producerRequestRevision()).setProducerStepId(x.producerStepId()).setProducerExecutionId(x.producerExecutionId()).setProducerRunId(x.producerRunId()).setProducerOutputId(x.producerOutputId()).setCreatedAt(now);r.setTenantId(s.tenantId());r.setClientId(s.clientId());return r;}
 private ControlledImageFollowupAuthorityDTO view(Scope s,ControlledImageIntentOperationGrantEntity o,boolean replay){var c=consents.findFollowupByConsent(s.tenantId(),s.clientId(),s.ownerJiacn(),o.getTaskId(),o.getConsentId());if(c==null)throw fail(Reason.UNAVAILABLE);List<ControlledImageFollowupAuthorityDTO.Source> src=readSources(o.getSourceSnapshotJson());return new ControlledImageFollowupAuthorityDTO(2,c.getConsentId(),c.getState(),Long.toString(c.getVersion()),o.getOperationGrantId(),o.getState(),Long.toString(o.getVersion()),o.getTaskId(),o.getConversationId(),Long.toString(o.getConversationGeneration()),o.getRequestId(),o.getStepId(),o.getExecutionIntentId(),o.getTargetAgentId(),o.getOperation(),o.getOwnerPayloadSha256(),o.getInstructionSha256(),o.getSourceSnapshotSha256(),src,new ControlledImageFollowupAuthorityDTO.ProviderBinding(c.getBindingId(),Long.toString(c.getBindingEpoch())),c.getModelId(),c.getCustody(),c.getOperatorPolicyRevision(),c.getPricingMode(),c.getMaxOutboundRequestAttempts(),Long.toString(c.getExpiresAt()),replay);}
 private List<ControlledImageFollowupAuthorityDTO.Source> readSources(String raw){return readAuthoritySources(raw).stream().map(ControlledImageFollowupAuthorityServiceImpl::sourceView).toList();}
 private static ControlledImageFollowupAuthorityDTO.Source sourceView(Source s){return new ControlledImageFollowupAuthorityDTO.Source(s.inputRef(),s.kind(),s.fileId(),s.fileVersion()==null?null:Integer.toString(s.fileVersion()),s.purpose(),s.conversationId(),s.conversationGeneration()==null?null:Long.toString(s.conversationGeneration()),s.assetId(),s.assetRevision()==null?null:Long.toString(s.assetRevision()),s.producerRequestId(),s.producerRequestRevision()==null?null:Long.toString(s.producerRequestRevision()),s.producerStepId(),s.producerExecutionId(),s.producerRunId(),s.producerOutputId(),s.contentMimeType(),Long.toString(s.byteLength()),s.sha256());}
 private static AgentTaskProviderCostConsentService.Scope consentScope(Scope s){return new AgentTaskProviderCostConsentService.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());}
 private String runtimeDigest(PreviewCommand command,String executionId,String runId){
  List<Map<String,Object>> inputs=new ArrayList<>();
  for(Source source:command.sources()){
   Map<String,Object> descriptor=new LinkedHashMap<>();
   if("TASK_LINKED_WORKSPACE_VERSION".equals(source.kind())){descriptor.put("fileId",source.fileId());descriptor.put("kind",source.kind());descriptor.put("purpose",source.purpose());descriptor.put("version",Integer.toString(source.fileVersion()));}
   else{descriptor.put("assetId",source.assetId());descriptor.put("assetRevision",Long.toString(source.assetRevision()));descriptor.put("conversationGeneration",Long.toString(source.conversationGeneration()));descriptor.put("conversationId",source.conversationId());descriptor.put("kind",source.kind());descriptor.put("producerExecutionId",source.producerExecutionId());descriptor.put("producerOutputId",source.producerOutputId());descriptor.put("producerRequestId",source.producerRequestId());descriptor.put("producerRunId",source.producerRunId());descriptor.put("producerStepId",source.producerStepId());}
   Map<String,Object> input=new LinkedHashMap<>();input.put("byteLength",Long.toString(source.byteLength()));input.put("contentMimeType",source.contentMimeType());input.put("inputRef",source.inputRef());input.put("sha256",source.sha256());input.put("source",descriptor);inputs.add(input);
  }
  Map<String,Object> domain=new LinkedHashMap<>();domain.put("conversationId",command.conversationId());domain.put("executionId",executionId);domain.put("inputs",inputs);domain.put("noReferencedMaterials",inputs.isEmpty());domain.put("operation",command.operation());domain.put("runId",runId);domain.put("schemaVersion",1);domain.put("taskId",command.taskId());return sha(write(domain));
 }

 private void validate(Scope s,PreviewCommand c){validScope(s);if(c==null||c.baseline()==null||c.sources()==null)throw fail(Reason.BAD_REQUEST);id(c.taskId(),100);id(c.conversationId(),100);id(c.interactionIdempotencyKey(),100);id(c.requestId(),100);id(c.stepId(),100);id(c.executionIntentId(),100);id(c.baseline().grantId(),100);id(c.baseline().targetAgentId(),100);if(c.conversationGeneration()<1||c.baseline().grantVersion()<1||c.baseline().taskVersion()<0||c.baseline().assignmentRevision()<0||c.baseline().requirementRevision()<1)throw fail(Reason.BAD_REQUEST);hash(c.baseline().requirementSha256());hash(c.instructionSha256());hash(c.ownerPayloadSha256());hash(c.sourceSnapshotSha256());if(!Set.of("GENERATE_IMAGE","EDIT_IMAGE").contains(c.operation())||c.instruction()==null||c.instruction().isBlank()||c.instruction().codePointCount(0,c.instruction().length())>4000||c.instruction().chars().anyMatch(Character::isISOControl)||c.sources().size()>16||("EDIT_IMAGE".equals(c.operation())&&c.sources().size()!=1))throw fail(Reason.BAD_REQUEST);int ordinal=0;for(Source x:c.sources()){ordinal++;if(!same("input_"+ordinal,x.inputRef()))throw fail(Reason.BAD_REQUEST);validateSource(x,c);}}
 private static void validateSource(Source x,PreviewCommand c){if(x==null||x.byteLength()<1||!Set.of("image/jpeg","image/png").contains(x.contentMimeType()))throw fail(Reason.BAD_REQUEST);hash(x.sha256());id(x.inputRef(),100);if("TASK_LINKED_WORKSPACE_VERSION".equals(x.kind())){id(x.fileId(),100);if(x.fileVersion()==null||x.fileVersion()<1||!"REFERENCE".equals(x.purpose())||!"GENERATE_IMAGE".equals(c.operation()))throw fail(Reason.BAD_REQUEST);}else if("CURRENT_CONVERSATION_ASSET".equals(x.kind())){if(!"EDIT_IMAGE".equals(c.operation())||!same(c.conversationId(),x.conversationId())||!Objects.equals(c.conversationGeneration(),x.conversationGeneration())||x.assetRevision()==null||x.assetRevision()<1||x.producerRequestRevision()==null||x.producerRequestRevision()<1)throw fail(Reason.BAD_REQUEST);id(x.assetId(),100);id(x.producerRequestId(),100);id(x.producerStepId(),100);id(x.producerExecutionId(),100);id(x.producerRunId(),100);id(x.producerOutputId(),100);}else throw fail(Reason.BAD_REQUEST);}
 private static void validScope(Scope s){if(s==null||!"0".equals(s.tenantId()))throw fail(Reason.BAD_REQUEST);id(s.clientId(),50);id(s.ownerJiacn(),50);if("0".equals(s.ownerJiacn()))throw fail(Reason.BAD_REQUEST);}
 private static void id(String v,int max){if(v==null||v.isBlank()||!v.equals(v.strip())||v.codePointCount(0,v.length())>max||v.chars().anyMatch(Character::isISOControl))throw fail(Reason.BAD_REQUEST);}
 private static void hash(String v){if(v==null||!v.matches("[0-9a-f]{64}"))throw fail(Reason.BAD_REQUEST);}
 private static void positive(long v){if(v<1||v>SAFE)throw fail(Reason.BAD_REQUEST);}
 private static boolean reservedOrConsumed(String state){return "RESERVED".equals(state)||"CONSUMED".equals(state);}
 private static boolean same(Object a,Object b){return Objects.equals(a,b);}
 private static boolean sameHash(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
 private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw fail(Reason.UNAVAILABLE,e);}}
 private static String sha(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 private static Failure fail(Reason r){return new Failure(r);}private static Failure fail(Reason r,Throwable c){return new Failure(r,c);}
 private static Failure translate(RuntimeException e){if(e instanceof Failure f)return f;if(e instanceof AgentTaskExecutionGrantException a)return fail(a.status()==404?Reason.NOT_FOUND_OR_FORBIDDEN:Reason.CONFLICT,a);if(e instanceof ControlledImageProviderOperatorPolicy.PolicyFailure p)return fail(p.reason()==ControlledImageProviderOperatorPolicy.Reason.SOURCE_UNAVAILABLE?Reason.UNAVAILABLE:Reason.CONFLICT,p);if(e instanceof DuplicateKeyException)return fail(Reason.CONFLICT,e);if(e instanceof DataAccessException||e instanceof TransactionException)return fail(Reason.UNAVAILABLE,e);return fail(Reason.UNAVAILABLE,e);}
}
