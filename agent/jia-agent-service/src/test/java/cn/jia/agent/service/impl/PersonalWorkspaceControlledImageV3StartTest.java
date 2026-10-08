package cn.jia.agent.service.impl;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import cn.jia.agent.config.*;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.user.security.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PersonalWorkspaceControlledImageV3StartTest {
 @Test void startReceiptIsSchemaThreeAndNeverCarriesFenceToken() {
  var provider=new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",
    "consent_1234567890abcdef1234567890abcdef","binding","1","model",16,1,1);
  var receipt=new PersonalWorkspaceExecutionService.ControlledProviderStartReceiptV3(3,true,"task","run","conversation",
    "execution","command","message","GENERATE_IMAGE","a".repeat(64),provider,1);
  assertTrue(receipt.started());assertEquals(3,receipt.schemaVersion());
  assertTrue(java.util.Arrays.stream(receipt.getClass().getRecordComponents()).noneMatch(c->c.getName().toLowerCase().contains("token")));
 }
 @Test void startReadsExternalBytesBeforeRootTransactionAndOnlyRevalidatesRowsInsideLateCheck()
     throws Exception {
  String source=java.nio.file.Files.readString(java.nio.file.Path.of(
    "src/main/java/cn/jia/agent/service/impl/PersonalWorkspaceExecutionServiceImpl.java"));
  int method=source.indexOf("beginControlledConversationProviderStartV3(");
  int bytes=source.indexOf("verifiedV3SourceBytes(scope,candidate)",method);
  int consume=source.indexOf("followupAuthority.consumeForStart",method);
  int rows=source.indexOf("verifiedV3SourceRows(scope,execution)",consume);
  int nextMethod=source.indexOf("private static ProviderExecution provider",method);
  assertTrue(method>=0&&bytes>method&&consume>bytes&&rows>consume&&nextMethod>rows);
  assertEquals(-1,source.substring(consume,nextMethod)
    .indexOf("verifiedV3SourceBytes(scope,execution)"));
 }

 @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

 @Test void rotationDuringSourceIoRejectsPaidStartBeforeAuthorityConsumption() throws Exception {
  var fixture=new StartFixture();
  when(fixture.storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
   assertFalse(fixture.transactionActive.get(), "external bytes must precede persistent fence locks");
   fixture.row.setRuntimeSessionGeneration(2L).setRuntimeInstanceId("replacement");
   return fixture.stored;
  });
  assertThrows(RuntimeException.class,fixture::start);
  verify(fixture.manager).rollback(fixture.status);
  verify(fixture.manager,never()).commit(any());
  verify(fixture.authority,never()).consumeForStart(any(),any(),any());
  verify(fixture.executions,never()).update(any());
 }

 @Test void currentProofFencesOriginalCommandAfterByteReadAndBeforeConsumption() throws Exception {
  var fixture=new StartFixture();
  when(fixture.authority.consumeForStart(any(),any(),any())).thenAnswer(call->{
   assertTrue(fixture.transactionActive.get());
   ControlledImageFollowupAuthorityService.StartCommand command=call.getArgument(1);
   assertEquals(fixture.command.commandId(),command.commandId());
   assertEquals(fixture.command.messageId(),command.messageId());
   assertEquals(fixture.command.inputSnapshotDigest(),command.inputSnapshotDigest());
   return new ControlledImageFollowupAuthorityService.StartReceipt("task","run","conversation","execution",
     command.commandId(),command.messageId(),command.operation(),command.inputSnapshotDigest(),
     command.providerExecution(),command.leaseVersion());
  });
  assertTrue(fixture.start().started());
  var order=inOrder(fixture.storage,fixture.manager,fixture.installations,fixture.rows,fixture.authority);
  order.verify(fixture.storage).read(any(),anyString(),anyString(),anyLong(),anyString());
  order.verify(fixture.manager).getTransaction(any());
  order.verify(fixture.installations).lock(StartFixture.ID);
  order.verify(fixture.rows).lockInScope("0","client",StartFixture.AGENT);
  order.verify(fixture.authority).consumeForStart(any(),any(),any());
  order.verify(fixture.manager).commit(fixture.status);
 }

 @Test void missingRuntimeProofCannotReadSourceOrConsumeStart() throws Exception {
  var fixture=new StartFixture();
  SecurityContextHolder.clearContext();
  assertThrows(RuntimeException.class,fixture::start);
  verify(fixture.authority,never()).consumeForStart(any(),any(),any());
  verify(fixture.storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
 }

 @Test void corruptSourceSnapshotRejectsBeforeRuntimeTransactionAndConsumption() throws Exception {
  var fixture=new StartFixture();
  when(fixture.workspace.findVersion("0","client","owner","file",1)).thenReturn(
    new PersonalWorkspaceVersionEntity().setContentHash("0".repeat(64)).setByteLength(22L).setContentMimeType("image/png"));
  assertThrows(RuntimeException.class,fixture::start);
  verify(fixture.manager,never()).getTransaction(any());
  verify(fixture.authority,never()).consumeForStart(any(),any(),any());
 }

 /** Real native service + real transaction-advised auth; DAO/storage/authority are explicit
  * collaborators. Cloud DB/root/paid-side-effect integration remains separate evidence. */
 private static final class StartFixture {
  static final String ID="rti_"+"1".repeat(32), AGENT="agt_"+"a".repeat(32);
  final PersonalWorkspaceExecutionDao executions=mock(PersonalWorkspaceExecutionDao.class);
  final PersonalWorkspaceDao workspace=mock(PersonalWorkspaceDao.class);
  final PersonalWorkspaceTaskLinkDao links=mock(PersonalWorkspaceTaskLinkDao.class);
  final AgentRuntimeDao rows=mock(AgentRuntimeDao.class);
  final AgentRuntimeV1InstallationDao installations=mock(AgentRuntimeV1InstallationDao.class);
  final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
  final ControlledImageFollowupAuthorityService authority=mock(ControlledImageFollowupAuthorityService.class);
  final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
  final TransactionStatus status=mock(TransactionStatus.class);
  final AtomicBoolean transactionActive=new AtomicBoolean();
  final PersonalWorkspaceExecutionService.RuntimeScope scope=new PersonalWorkspaceExecutionService.RuntimeScope(
    "0","client","owner",AGENT,"boot");
  final AgentRuntimeEntity row;
  final PersonalWorkspaceStorage.StoredContent stored;
  final PersonalWorkspaceExecutionService.ControlledProviderStartV3 command;
  final PersonalWorkspaceExecutionServiceImpl service;

  StartFixture() throws Exception {
   byte[] content="immutable-source-bytes".getBytes(StandardCharsets.UTF_8);
   String hash=sha(content);
   stored=new PersonalWorkspaceStorage.StoredContent(content,hash,content.length,"image/png");
   var installation=new AgentRuntimeV1InstallationEntity().setInstallationId(ID).setCanonicalAgentId(AGENT).setStatus("ACTIVE");
   installation.setTenantId("0");installation.setClientId("client");
   var identity=new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT).setBindingId(3L);
   var binding=new AgentPersonaBindingEntity().setId(3L);
   var identities=mock(AgentIdentityService.class);
   when(identities.requireRegistrationIdentityInScope("0","client","owner",AGENT)).thenReturn(identity);
   when(identities.requireActiveBinding(identity,null)).thenReturn(binding);
   var accounts=mock(AccountSecurityService.class);
   when(accounts.findUniqueByExactJiacn("owner")).thenReturn(Optional.of(new AccountSecuritySnapshot(7,"owner",AccountState.ACTIVE,2)));
   row=new AgentRuntimeEntity().setAgentId(AGENT).setOwnerJiacn("owner").setBindingId(3L)
     .setRuntimeInstallationId(ID).setRuntimeHostId("host").setRuntimeInstanceId("boot")
     .setRuntimeSessionGeneration(1L).setTokenHash("urs1:"+"b".repeat(64)+":7:2");
   row.setTenantId("0");row.setClientId("client");
   when(installations.lock(ID)).thenReturn(installation);
   when(rows.lockInScope("0","client",AGENT)).thenReturn(row);
   var auth=new AgentRuntimeAuthenticationService(rows,installations,mock(AgentIdentityRegistryDao.class),identities,accounts,mock(AgentTaskEventsGate.class));
   when(manager.getTransaction(any())).thenAnswer(i->{transactionActive.set(true);return status;});
   doAnswer(i->{transactionActive.set(false);return null;}).when(manager).commit(status);
   doAnswer(i->{transactionActive.set(false);return null;}).when(manager).rollback(status);
   var advice=new TransactionInterceptor();advice.setTransactionManager(manager);
   advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
   var proxy=new ProxyFactory(auth);proxy.setProxyTargetClass(true);proxy.addAdvice(advice);
   var authentication=new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope("0","client","owner",AGENT,"boot"));
   authentication.setDetails(new AgentRuntimeAuthenticationService.Proof(authentication.getPrincipal(),ID,"host",1,"b".repeat(64),7,2));
   SecurityContextHolder.getContext().setAuthentication(authentication);

   var source=new ControlledImageExecutionSourceV3Entity().setExecutionId("execution").setOwnerJiacn("owner")
     .setInputOrdinal(1).setInputRef("input_1").setSourceKind("TASK_LINKED_WORKSPACE_VERSION")
     .setFileId("file").setFileVersion(1).setPurpose("REFERENCE").setCreatedAt(1L)
     .setContentMimeType("image/png").setByteLength((long)content.length).setContentSha256(hash);
   source.setTenantId("0");source.setClientId("client");
   var descriptor=new LinkedHashMap<String,Object>();descriptor.put("fileId","file");descriptor.put("kind","TASK_LINKED_WORKSPACE_VERSION");
   descriptor.put("purpose","REFERENCE");descriptor.put("version","1");
   var json=new ObjectMapper();source.setSourceJson(json.writeValueAsString(descriptor));
   var input=new LinkedHashMap<String,Object>();input.put("byteLength",Long.toString(content.length));input.put("contentMimeType","image/png");
   input.put("inputRef","input_1");input.put("sha256",hash);input.put("source",descriptor);
   var domain=new LinkedHashMap<String,Object>();domain.put("conversationId","conversation");domain.put("executionId","execution");
   domain.put("inputs",List.of(input));domain.put("noReferencedMaterials",false);domain.put("operation","GENERATE_IMAGE");
   domain.put("runId","run");domain.put("schemaVersion",1);domain.put("taskId","task");
   String digest=sha(json.writeValueAsBytes(domain));
   var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setTaskId("task").setRunId("run")
     .setConversationId("conversation").setTargetAgentId(AGENT).setExecutionMode("CONVERSATION").setExecutionState("QUEUED")
     .setExecutionProtocolVersion(3).setPermittedOperation("GENERATE_IMAGE").setRuntimeInputSnapshotDigest(digest);
   when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
   var sources=mock(ControlledImageExecutionSourceV3Dao.class);
   when(sources.list("0","client","owner","execution")).thenReturn(List.of(source));
   when(workspace.findFile("0","client","owner","file")).thenReturn(new PersonalWorkspaceFileEntity().setState("ACTIVE"));
   when(workspace.findVersion("0","client","owner","file",1)).thenReturn(new PersonalWorkspaceVersionEntity()
     .setContentHash(hash).setByteLength((long)content.length).setContentMimeType("image/png").setStorageUri("private-source"));
   when(links.hasActiveExecutionInputLink("0","client","owner","task","file",1)).thenReturn(true);
   when(storage.maxContentBytes()).thenReturn(4096L);
   when(storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(i->{
    assertFalse(transactionActive.get());return stored;
   });
   service=new PersonalWorkspaceExecutionServiceImpl(executions,workspace,links,rows,storage,mock(PersonalWorkspaceWriteService.class),
     new PersonalWorkspaceExecutionProperties(List.of("image/png")));
   service.setRuntimeAuthentication((AgentRuntimeAuthenticationService)proxy.getProxy());
   service.setControlledImageFollowupV3(authority,sources);
   ReflectionTestUtils.setField(service,"conversationExecutionEnabled",true);
   var provider=new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1","consent","binding","1","model",16,1,1);
   command=new PersonalWorkspaceExecutionService.ControlledProviderStartV3(3,"pwe_cmd_"+sha("command\nexecution".getBytes(StandardCharsets.UTF_8)),
     "pwe_msg_"+sha("message\nexecution".getBytes(StandardCharsets.UTF_8)),"execution","GENERATE_IMAGE",digest,provider,
     new PersonalWorkspaceExecutionService.ConversationFence(1,"original-fence"));
  }
  PersonalWorkspaceExecutionService.ControlledProviderStartReceiptV3 start() {
   return service.beginControlledConversationProviderStartV3(scope,"task","run",command);
  }
  static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
 }
}
