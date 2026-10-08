package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import cn.jia.agent.entity.ControlledImageExecutionSourceV3Entity;
import static org.mockito.Mockito.*;
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
 @Test void workspaceSourceHttpJsonContainsOnlyItsFourProtocolFields() throws Exception {
  var source=new PersonalWorkspaceExecutionService.RuntimeSource("TASK_LINKED_WORKSPACE_VERSION",
    "file","2","REFERENCE",null,null,null,null,null,null,null,null,null);
  var json=sourceHttpJson(source,"GENERATE_IMAGE");
  assertEquals(java.util.Set.of("kind","fileId","version","purpose"),
    new java.util.HashSet<>(json.propertyNames()));
  assertEquals("2",json.get("version").textValue());
  assertEquals("REFERENCE",json.get("purpose").textValue());
 }
 @Test void conversationAssetHttpJsonContainsOnlyItsTenProtocolFields() throws Exception {
  var source=new PersonalWorkspaceExecutionService.RuntimeSource("CURRENT_CONVERSATION_ASSET",null,null,null,
    "conversation","1","asset","2","request","step","execution0","run0","output_1");
  var json=sourceHttpJson(source,"EDIT_IMAGE");
  assertEquals(java.util.Set.of("kind","conversationId","conversationGeneration","assetId","assetRevision",
    "producerRequestId","producerStepId","producerExecutionId","producerRunId","producerOutputId"),
    new java.util.HashSet<>(json.propertyNames()));
  assertEquals("2",json.get("assetRevision").textValue());
  assertEquals("1",json.get("conversationGeneration").textValue());
 }
 private static tools.jackson.databind.JsonNode sourceHttpJson(
   PersonalWorkspaceExecutionService.RuntimeSource source,String operation) throws Exception {
  // Exercise the actual Spring HTTP converter, not a hand-written JSON fixture or record accessors.
  var snapshot=new PersonalWorkspaceExecutionService.ConversationInputSnapshotV3(3,"execution",1,operation,
    "a".repeat(64),false,List.of(new PersonalWorkspaceExecutionService.RuntimeInputV3(
      "input_1",source,"image/png","4","b".repeat(64))));
  var output=new org.springframework.mock.http.MockHttpOutputMessage();
  // Production applies global response advice before the converter. Its bean-to-Map
  // projection previously reintroduced null union fields despite @JsonInclude(NON_NULL).
  var method=cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.class.getMethod(
    "inputsV3",String.class,String.class,
    cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.FenceRequest.class,
    jakarta.servlet.http.HttpServletRequest.class,org.springframework.security.core.Authentication.class);
  var advised=new cn.jia.core.security.SensitiveResponseBodyAdvice(
    new cn.jia.core.security.SensitiveResponseProperties()).beforeBodyWrite(snapshot,
      new org.springframework.core.MethodParameter(method,-1),org.springframework.http.MediaType.APPLICATION_JSON,
      org.springframework.http.converter.json.JacksonJsonHttpMessageConverter.class,null,null);
  new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter().write(
    advised,org.springframework.http.MediaType.APPLICATION_JSON,output);
  var root=tools.jackson.databind.json.JsonMapper.builder().build().readTree(output.getBodyAsString());
  assertEquals(3,root.get("schemaVersion").intValue());
  assertEquals(operation,root.get("operation").textValue());
  assertEquals("4",root.get("inputs").get(0).get("byteLength").textValue());
  return root.get("inputs").get(0).get("source");
 }
 @Test void ordinarySourcesUseActualRuntimeRowValidationAndHttpProjectionForCrossClientWire() throws Exception {
  var sourceDao=mock(cn.jia.agent.dao.ControlledImageExecutionSourceV3Dao.class);
  var implementation=mock(PersonalWorkspaceExecutionServiceImpl.class,CALLS_REAL_METHODS);
  org.springframework.test.util.ReflectionTestUtils.setField(implementation,"followupSources",sourceDao);
  byte[] bytes=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGPgEpH7DwABpAE8k4sOtwAAAABJRU5ErkJggg==");
  String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  var descriptor=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredMethod("v3SourceDescriptor",ControlledImageExecutionSourceV3Entity.class,String.class);descriptor.setAccessible(true);
  var rowCheck=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredMethod("verifiedV3SourceRows",PersonalWorkspaceExecutionService.RuntimeScope.class,PersonalWorkspaceExecutionEntity.class);rowCheck.setAccessible(true);
  var project=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredMethod("runtimeInputV3",ControlledImageExecutionSourceV3Entity.class);project.setAccessible(true);
  var canonical=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredMethod("v3Json",Object.class);canonical.setAccessible(true);
  var cases=new ArrayList<Map<String,Object>>();
  for(String mode:List.of("mixed-generation","workspace-edit","asset-generation")){
   String operation="workspace-edit".equals(mode)?"EDIT_IMAGE":"GENERATE_IMAGE";
   var workspace=sourceRow("input_1",1,bytes.length,hash).setSourceKind("TASK_LINKED_WORKSPACE_VERSION").setFileId("file")
     .setFileVersion(2).setPurpose("INPUT");
   var asset=sourceRow("mixed-generation".equals(mode)?"input_2":"input_1","mixed-generation".equals(mode)?2:1,bytes.length,hash)
     .setSourceKind("CURRENT_CONVERSATION_ASSET").setConversationId("conversation").setConversationGeneration(2L)
     .setAssetId("asset").setAssetRevision(1L).setProducerRequestId("request_previous").setProducerRequestRevision(1L)
     .setProducerStepId("step_previous").setProducerExecutionId("execution_previous").setProducerRunId("run_previous").setProducerOutputId("output_1");
   var rows="mixed-generation".equals(mode)?List.of(workspace,asset):List.of("workspace-edit".equals(mode)?workspace:asset);
   var inputs=new ArrayList<Map<String,Object>>();var nativeInputs=new ArrayList<PersonalWorkspaceExecutionService.RuntimeInputV3>();
   for(var row:rows){var source=descriptor.invoke(null,row,operation);row.setSourceJson((String)canonical.invoke(null,source));
    inputs.add(Map.of("inputRef",row.getInputRef(),"source",source,"contentMimeType","image/png","byteLength",Long.toString(row.getByteLength()),"sha256",hash));
    nativeInputs.add((PersonalWorkspaceExecutionService.RuntimeInputV3)project.invoke(null,row));
   }
   var domain=Map.of("schemaVersion",1,"executionId","execution","taskId","task","runId","run","conversationId","conversation",
     "operation",operation,"noReferencedMaterials",false,"inputs",inputs);
   String serialized=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build().writeValueAsString(domain);
   String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
   var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setTaskId("task").setRunId("run")
     .setConversationId("conversation").setPermittedOperation(operation).setExecutionProtocolVersion(3).setRuntimeInputSnapshotDigest(digest);
   when(sourceDao.list("0","client","owner","execution")).thenReturn(rows);
   assertEquals(rows,rowCheck.invoke(implementation,new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime"),execution));
   var provider=new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1","consent_1234567890abcdef1234567890abcdef","binding","1","operator-model",16,1,1);
   var command=new PersonalWorkspaceExecutionService.ControlledConversationRuntimeCommandV3(3,"execution","task","run","conversation","command","message",operation,"draw",digest,"image/png","output_1",provider);
   var snapshot=new PersonalWorkspaceExecutionService.ConversationInputSnapshotV3(3,"execution",1,operation,digest,false,nativeInputs);
   cases.add(Map.of("caseId",mode,"command",command,"inputSnapshot",httpJson(snapshot),"inputBytesBase64",java.util.Base64.getEncoder().encodeToString(bytes)));
   rows.getFirst().setContentSha256("f".repeat(64));
   var changed=assertThrows(InvocationTargetException.class,()->rowCheck.invoke(implementation,new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime"),execution));
   assertInstanceOf(PersonalWorkspaceExecutionService.Failure.class,changed.getCause());
  }
  // Captured from JUnit stdout for the Client parser/materializer regression, not Provider evidence.
  System.out.println("MMD_UNIFIED_EXECUTION_WIRE="+tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(cases));
 }
 private static ControlledImageExecutionSourceV3Entity sourceRow(String ref,int ordinal,long length,String hash){
  var row=new ControlledImageExecutionSourceV3Entity().setExecutionId("execution").setOwnerJiacn("owner").setInputRef(ref).setInputOrdinal(ordinal)
    .setContentMimeType("image/png").setByteLength(length).setContentSha256(hash).setCreatedAt(1L);
  row.setTenantId("0");row.setClientId("client");return row;
 }
 private static Object httpJson(PersonalWorkspaceExecutionService.ConversationInputSnapshotV3 snapshot) throws Exception {
  var output=new org.springframework.mock.http.MockHttpOutputMessage();
  var method=cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.class.getMethod("inputsV3",String.class,String.class,
    cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.FenceRequest.class,jakarta.servlet.http.HttpServletRequest.class,
    org.springframework.security.core.Authentication.class);
  var advised=new cn.jia.core.security.SensitiveResponseBodyAdvice(new cn.jia.core.security.SensitiveResponseProperties()).beforeBodyWrite(snapshot,
    new org.springframework.core.MethodParameter(method,-1),org.springframework.http.MediaType.APPLICATION_JSON,
    org.springframework.http.converter.json.JacksonJsonHttpMessageConverter.class,null,null);
  new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter().write(advised,org.springframework.http.MediaType.APPLICATION_JSON,output);
  return tools.jackson.databind.json.JsonMapper.builder().build().readValue(output.getBodyAsString(),Map.class);
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
  int stageWrite=source.indexOf("persistStagedOutput(",stage);
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
