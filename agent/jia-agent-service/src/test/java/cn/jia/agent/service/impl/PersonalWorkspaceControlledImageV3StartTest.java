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


 @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }











 /** Real native service + real transaction-advised auth; DAO/storage/authority are explicit
  * collaborators. Cloud DB/root/paid-side-effect integration remains separate evidence. */
 static final class StartFixture {
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
  PersonalWorkspaceExecutionService.ControlledProviderStartV3 command;
  final PersonalWorkspaceExecutionEntity execution;
  final ControlledImageExecutionSourceV3Dao sources;
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
   var authentication=org.springframework.beans.BeanUtils.instantiateClass(AgentRuntimeAuthentication.class.getDeclaredConstructor(AgentRuntimeAuthentication.Scope.class),new AgentRuntimeAuthentication.Scope("0","client","owner",AGENT,"boot"));
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
   execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setTaskId("task").setRunId("run")
     .setConversationId("conversation").setTargetAgentId(AGENT).setExecutionMode("CONVERSATION").setExecutionState("QUEUED")
     .setExecutionProtocolVersion(3).setPermittedOperation("GENERATE_IMAGE").setRuntimeInputSnapshotDigest(digest);
   execution.setTenantId("0"); execution.setClientId("client"); execution.setOwnerJiacn("owner");
   execution.setTaskGrantId("grant").setTaskGrantVersion(1L);
   when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
   sources=mock(ControlledImageExecutionSourceV3Dao.class);
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
        wireAssigneeFixture(service,executions);
   service.setRuntimeAuthentication((AgentRuntimeAuthenticationService)proxy.getProxy());
   service.setControlledImageFollowupV3(authority,sources);
   ReflectionTestUtils.setField(service,"conversationExecutionEnabled",true);
   var provider=new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1","consent","binding","1","model",16,1,1);
   command=new PersonalWorkspaceExecutionService.ControlledProviderStartV3(3,"pwe_cmd_"+sha("command\nexecution".getBytes(StandardCharsets.UTF_8)),
     "pwe_msg_"+sha("message\nexecution".getBytes(StandardCharsets.UTF_8)),"execution","GENERATE_IMAGE",digest,provider,
     new PersonalWorkspaceExecutionService.ConversationFence(1,"original-fence"));
  }
  void useAssetSource() throws Exception {
   var source=new ControlledImageExecutionSourceV3Entity().setExecutionId("execution").setOwnerJiacn("owner")
     .setInputOrdinal(1).setInputRef("input_1").setSourceKind("CURRENT_CONVERSATION_ASSET").setCreatedAt(1L)
     .setConversationId("conversation").setConversationGeneration(1L).setAssetId("asset").setAssetRevision(1L)
     .setProducerRequestId("request").setProducerRequestRevision(1L).setProducerStepId("step")
     .setProducerExecutionId("producer").setProducerRunId("producer-run").setProducerOutputId("output_1")
     .setContentMimeType("image/png").setByteLength(stored.byteLength()).setContentSha256(stored.sha256());
   source.setTenantId("0");source.setClientId("client");
   var descriptor=new LinkedHashMap<String,Object>();descriptor.put("assetId","asset");descriptor.put("assetRevision","1");
   descriptor.put("conversationGeneration","1");descriptor.put("conversationId","conversation");
   descriptor.put("kind","CURRENT_CONVERSATION_ASSET");descriptor.put("producerExecutionId","producer");
   descriptor.put("producerOutputId","output_1");descriptor.put("producerRequestId","request");
   descriptor.put("producerRunId","producer-run");descriptor.put("producerStepId","step");
   var json=new ObjectMapper();source.setSourceJson(json.writeValueAsString(descriptor));
   var input=new LinkedHashMap<String,Object>();input.put("byteLength",Long.toString(stored.byteLength()));
   input.put("contentMimeType","image/png");input.put("inputRef","input_1");input.put("sha256",stored.sha256());input.put("source",descriptor);
   var domain=new LinkedHashMap<String,Object>();domain.put("conversationId","conversation");domain.put("executionId","execution");
   domain.put("inputs",List.of(input));domain.put("noReferencedMaterials",false);domain.put("operation","GENERATE_IMAGE");
   domain.put("runId","run");domain.put("schemaVersion",1);domain.put("taskId","task");
   String digest=sha(json.writeValueAsBytes(domain));execution.setRuntimeInputSnapshotDigest(digest);
   when(sources.list("0","client","owner","execution")).thenReturn(List.of(source));
   var output=new PersonalWorkspaceExecutionOutputEntity().setExecutionId("producer").setOutputId("output_1")
     .setOutputState("COMMITTED").setOutputPurpose("CONVERSATION").setContentHash(stored.sha256())
     .setByteLength(stored.byteLength()).setContentMimeType("image/png").setStorageUri("asset-source");
   when(executions.findOutput("0","client","owner","producer","output_1")).thenReturn(output);
   command=new PersonalWorkspaceExecutionService.ControlledProviderStartV3(3,command.commandId(),command.messageId(),command.executionId(),
     command.operation(),digest,command.providerExecution(),command.fence());
  }

  static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
 }

    /** Existing fixture has one authenticated owner. Cross-owner fixtures supply explicit grant authority separately. */
    private static void wireAssigneeFixture(cn.jia.agent.service.PersonalWorkspaceExecutionService instance,
            cn.jia.agent.dao.PersonalWorkspaceExecutionDao rows) {
        var service=(cn.jia.agent.service.impl.PersonalWorkspaceExecutionServiceImpl)instance;
        var authority=org.mockito.Mockito.mock(cn.jia.agent.service.TaskAssigneeAuthorityService.class);
        org.mockito.Mockito.when(authority.resolveForAssignment(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(i->new cn.jia.agent.service.TaskAssigneeAuthorityService.Target(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),1L,null));
        org.mockito.Mockito.when(authority.requireGrantTarget(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(i->new cn.jia.agent.service.TaskAssigneeAuthorityService.Target(i.getArgument(0),i.getArgument(1),i.getArgument(2),StartFixture.AGENT,1L,null));
        service.setAssigneeAuthority(authority);
        org.mockito.Mockito.when(rows.findForRuntime(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(i->rows.findByTaskRun(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(4),i.getArgument(5)));
    }
}
