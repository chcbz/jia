package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PersonalWorkspaceControlledImageV3ExecutionTest {
 @Test void v3CommandAndInputProjectionCarryExactOperationDigestAndLineage() {
  var provider=new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",
    "consent_1234567890abcdef1234567890abcdef","binding","1","model",16,1,1);
  var command=new PersonalWorkspaceExecutionService.ControlledConversationRuntimeCommandV3(3,"execution","task","run",
    "conversation","command","message","EDIT_IMAGE","edit","a".repeat(64),"image/png","output_1",provider);
  assertEquals(3,command.schemaVersion());assertEquals("EDIT_IMAGE",command.operation());assertEquals(provider,command.providerExecution());
  var source=new PersonalWorkspaceExecutionService.RuntimeSource("CURRENT_CONVERSATION_ASSET",null,null,null,
    "conversation","1","asset","1","request","step","execution0","run0","output_1");
  var snapshot=new PersonalWorkspaceExecutionService.ConversationInputSnapshotV3(3,"execution",1,"EDIT_IMAGE",
    "a".repeat(64),false,List.of(new PersonalWorkspaceExecutionService.RuntimeInputV3("input_1",source,"image/png","4","b".repeat(64))));
  assertEquals(1,snapshot.inputs().size());assertFalse(snapshot.noReferencedMaterials());
 }
 @Test void v3InboxChecksCurrentConversationAclBeforeReturningRuntimeAuthority() throws Exception {
  String source=java.nio.file.Files.readString(java.nio.file.Path.of(
    "src/main/java/cn/jia/agent/service/impl/PersonalWorkspaceExecutionServiceImpl.java"));
  int method=source.indexOf("runtimeControlledImageV3Commands(");
  int acl=source.indexOf("requireCurrentControlledConversationAccess",method);
  int authority=source.indexOf("followupAuthority.runtimeAuthority",method);
  assertTrue(method>=0&&acl>method&&authority>acl);
 }
 @Test void defaultOffLegacyExecutionInsertsDoNotReferenceV3OnlyProtocolColumn() throws Exception {
  String source=java.nio.file.Files.readString(java.nio.file.Path.of(
    "src/main/java/cn/jia/agent/service/impl/PersonalWorkspaceExecutionServiceImpl.java"));
  int privateCreate=source.indexOf("PersonalWorkspaceExecutionEntity execution = new PersonalWorkspaceExecutionEntity()");
  int privateInsert=source.indexOf("executions.insert(execution)",privateCreate);
  assertTrue(privateCreate>=0&&privateInsert>privateCreate);
  assertFalse(source.substring(privateCreate,privateInsert).contains("setExecutionProtocolVersion"));

  int conversation=source.indexOf("ExecutionView createConversation(");
  int catalog=source.indexOf("boolean executionProtocolColumn=command.controlledImage()",conversation);
  int root=source.indexOf("executeWithLockedTaskRootInOwnerScope",catalog);
  int row=source.indexOf("PersonalWorkspaceExecutionEntity row=new PersonalWorkspaceExecutionEntity()",root);
  int failClosedV3=source.indexOf("if(controlledProtocol==3&&!executionProtocolColumn)",catalog);
  int guardedProtocol=source.indexOf("if(executionProtocolColumn) row.setExecutionProtocolVersion(controlledProtocol)",row);
  int insert=source.indexOf("executions.insert(row)",guardedProtocol);
  assertTrue(conversation>=0&&catalog>conversation&&failClosedV3>catalog&&root>failClosedV3
    &&row>root&&guardedProtocol>row&&insert>guardedProtocol);
  assertFalse(source.substring(row,guardedProtocol).contains("setExecutionProtocolVersion"));
 }

 @Test void protocolThreeCannotFallbackToLegacyInputsOrPublishResultsBeforeStart() throws Exception {
  String source=java.nio.file.Files.readString(java.nio.file.Path.of(
    "src/main/java/cn/jia/agent/service/impl/PersonalWorkspaceExecutionServiceImpl.java"));
  int legacyInputs=source.indexOf("ConversationInputSnapshot conversationInputs(");
  int rejectV3=source.indexOf("Objects.equals(3, execution.getExecutionProtocolVersion())",legacyInputs);
  int legacyProjection=source.indexOf("verifiedConversationInputs(scope,execution)",legacyInputs);
  assertTrue(legacyInputs>=0&&rejectV3>legacyInputs&&legacyProjection>rejectV3);

  int stage=source.indexOf("StagedOutput stageConversationOutput(");
  int stageAuthority=source.indexOf("\"RESULT\"",stage);
  int stageFence=source.indexOf("requireControlledV3StartedForResult(execution)",stage);
  int stageWrite=source.indexOf("stageOutputLocked(",stage);
  assertTrue(stage>=0&&stageAuthority>stage&&stageFence>stageAuthority&&stageWrite>stageFence);
  int commit=source.indexOf("CommitView commitConversationOutput(");
  int commitAuthority=source.indexOf("\"RESULT\"",commit);
  int commitFence=source.indexOf("requireControlledV3StartedForResult(execution)",commit);
  int commitWrite=source.indexOf("commitConversationOutputs(",commit);
  assertTrue(commit>=0&&commitAuthority>commit&&commitFence>commitAuthority&&commitWrite>commitFence);
  int failureMethod=source.indexOf("ExecutionView failConversation(");
  int failureAuthority=source.indexOf("\"FAILURE\"",failureMethod);
  assertTrue(failureMethod>=0&&failureAuthority>failureMethod);

  Method guard=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredMethod(
    "requireControlledV3StartedForResult",PersonalWorkspaceExecutionEntity.class);
  guard.setAccessible(true);
  var notStarted=new PersonalWorkspaceExecutionEntity().setExecutionProtocolVersion(3);
  var thrown=assertThrows(InvocationTargetException.class,()->guard.invoke(null,notStarted));
  var failure=assertInstanceOf(PersonalWorkspaceExecutionService.Failure.class,thrown.getCause());
  assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,failure.getReason());
  notStarted.setConversationProviderStartedAt(1L).setConversationProviderLeaseVersion(1L);
  assertDoesNotThrow(()->guard.invoke(null,notStarted));
  assertDoesNotThrow(()->guard.invoke(null,
    new PersonalWorkspaceExecutionEntity().setExecutionProtocolVersion(2)));
 }

}
