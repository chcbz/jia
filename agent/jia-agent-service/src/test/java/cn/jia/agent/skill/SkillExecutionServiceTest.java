package cn.jia.agent.skill;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.agent.security.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import static cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Core service fences and replay; mocked repositories do NOT prove database lock serialization. */
public class SkillExecutionServiceTest {
    final OwnerScope owner=new OwnerScope("0","client","owner");
    final RuntimeScope runtime=new RuntimeScope("0","client","owner","agent","runtime");
    final PersonalWorkspaceExecutionDao pwe=mock(PersonalWorkspaceExecutionDao.class);
    final SkillExecutionDao attachments=mock(SkillExecutionDao.class);
    final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    final InstalledSkillDescriptorLookup installed=mock(InstalledSkillDescriptorLookup.class);
    final SkillCapabilityLookup capabilities=mock(SkillCapabilityLookup.class);
    final TaskSkillPolicyService policy=mock(TaskSkillPolicyService.class);
    final WorkspaceConversationAccessService conversations=mock(WorkspaceConversationAccessService.class);
    PersonalWorkspaceExecutionServiceImpl service;
    PersonalWorkspaceExecutionEntity execution;
    SkillExecutionEntity skill;
    final Map<String,Object> outputs=new LinkedHashMap<>();
    final ConversationFence fence=new ConversationFence(1,"fence-token");
    static final String HASH="a".repeat(64);
    static String json(Object value) {return JsonMapper.builder().build().writeValueAsString(canonical(value));}
    static Object canonical(Object v) {if(v instanceof Map<?,?> m){var r=new TreeMap<String,Object>();for(var e:m.entrySet())r.put(e.getKey().toString(),canonical(e.getValue()));return r;}if(v instanceof List<?> l)return l.stream().map(SkillExecutionServiceTest::canonical).toList();return v;}
    static String sha(String v) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}}
    @BeforeEach void setup() throws Exception {
        when(storage.maxContentBytes()).thenReturn(100000L);
        var runtimes=mock(AgentRuntimeDao.class);var agent=new AgentRuntimeEntity().setAgentId("agent").setOwnerJiacn("owner");agent.setTenantId("0");agent.setClientId("client");
        when(runtimes.findCandidateRosterByOwner("client","owner")).thenReturn(List.of(agent));
        service=new PersonalWorkspaceExecutionServiceImpl(pwe,mock(PersonalWorkspaceDao.class),mock(PersonalWorkspaceTaskLinkDao.class),runtimes,storage,mock(PersonalWorkspaceWriteService.class),new PersonalWorkspaceExecutionProperties(List.of("image/png","text/plain")));
        service.setSkillExecutionDependencies(attachments,installed,capabilities,policy);
        var root=new AgentTaskMetaEntity().setTaskId("task").setAssignedAgentId("agent").setTaskVersion(7L).setOwnerJiacn("owner");root.setTenantId("0");root.setClientId("client");
        var transactions=mock(AgentTaskMutationTransaction.class);
        when(transactions.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any())).thenAnswer(i->((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(root));
        service.setConversationAdmission(grants,transactions);
        service.setTaskExecutionDependencies(conversations,mock(AgentTaskWorkItemDao.class),mock(AgentWorkItemLeaseService.class));
        when(conversations.requireAccessible(any(),eq("42"))).thenReturn(new WorkspaceConversationAccessService.ConversationView("42","bounty","task:task","task",List.of("agent"),1,1));
        when(grants.admit(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),eq("EXECUTE_SKILL"),eq(false))).thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,7,"agent","EXECUTE_SKILL",false));
        var auth=mock(AgentRuntimeAuthenticationService.class);when(auth.withNativeFence(any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());service.setRuntimeAuthentication(auth);
        var principal=org.springframework.beans.BeanUtils.instantiateClass(AgentRuntimeAuthentication.class.getDeclaredConstructor(AgentRuntimeAuthentication.Scope.class),new AgentRuntimeAuthentication.Scope("0","client","owner","agent","runtime"));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(),"rti_"+"1".repeat(32),"host",2,"b".repeat(64),7,2));SecurityContextHolder.getContext().setAuthentication(principal);
        ReflectionTestUtils.setField(service,"conversationExecutionEnabled",true);
        var dataSchema=Map.of("$schema",SkillManifestCodec.DIALECT,"type","object","additionalProperties",false,"properties",Map.of("answer",Map.of("type","string")),"required",List.of("answer"));
        outputs.put("dataSchema",dataSchema);outputs.put("mimeTypes",List.of("image/png","application/x-custom"));outputs.put("multiple",true);outputs.put("artifactsRequired",false);
        var manifest=new LinkedHashMap<String,Object>();manifest.put("runner","native-model");manifest.put("entrypoint","SKILL.md");manifest.put("requirements",Map.of());manifest.put("outputs",outputs);
        manifest.put("inputs",Map.of("minItems",0,"maxItems",32,"acceptedMimeTypes",List.of("image/png")));manifest.put("parametersSchema",Map.of("$schema",SkillManifestCodec.DIALECT,"type","object","additionalProperties",false,"properties",Map.of()));
        when(installed.read(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(new InstalledSkillDescriptorLookup.Descriptor("analysis","1.0.0","install",HASH,HASH,json(manifest),"rti_"+"1".repeat(32),"host",null))));
        var sessionFence=new AgentManagedSessionLookup.SessionFence("rti_"+"1".repeat(32),"host","runtime",2);
        when(policy.read(any(),eq("task"))).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,7,sessionFence));
        when(capabilities.lookup(any(),eq(sessionFence))).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",sessionFence,new SkillCapabilityLookup.Catalog("1",List.of(new SkillCapabilityLookup.RuntimeSkill("analysis","1.0.0",HASH,HASH,"native-model",SkillCapabilityLookup.Readiness.READY,null,HASH,null)))));
        var snapshot=snapshot();String digest=sha(json(snapshot));
        execution=new PersonalWorkspaceExecutionEntity().setExecutionId("exec").setTaskId("task").setRunId("run").setConversationId("42").setTargetAgentId("agent").setOwnerJiacn("owner")
                .setExecutionMode("CONVERSATION").setExecutionState("QUEUED").setExecutionProtocolVersion(4).setPermittedOperation("EXECUTE_SKILL").setTaskGrantId("grant").setTaskGrantVersion(1L).setAssignmentRevision(7L)
                .setConversationLeaseVersion(1L).setConversationLeaseToken(fence.token()).setConversationLeaseRuntimeId("runtime").setConversationLeaseExpiresAt(System.currentTimeMillis()+900000);
        execution.setTenantId("0");execution.setClientId("client");
        skill=new SkillExecutionEntity().setTenantId("0").setClientId("client").setOwnerJiacn("owner").setExecutionId("exec").setSkillKey("analysis").setSkillVersion("1.0.0")
                .setPackageSha256(HASH).setManifestSha256(HASH).setSnapshotJson(json(snapshot)).setSnapshotSha256(digest).setPhase("RUNNING").setVersion(0L).setEffectsJson("{}");
        when(pwe.findByTaskRun("0","client","owner","task","run")).thenAnswer(i->execution);when(pwe.lockByTaskRun("0","client","owner","task","run")).thenAnswer(i->execution);
        when(pwe.find(eq("0"),eq("client"),eq("owner"),anyString())).thenAnswer(i->execution);when(pwe.lock(eq("0"),eq("client"),eq("owner"),anyString())).thenAnswer(i->execution);
        when(attachments.find(eq("0"),eq("client"),eq("owner"),anyString())).thenAnswer(i->skill);when(attachments.lock(eq("0"),eq("client"),eq("owner"),anyString())).thenAnswer(i->skill);
        when(attachments.compareAndSet(any(),anyLong(),anyString())).thenReturn(true);when(pwe.lockOutputs(eq("0"),eq("client"),eq("owner"),anyString())).thenReturn(List.of());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    Map<String,Object> snapshot() {
        var s=new LinkedHashMap<String,Object>();s.put("schemaVersion",1);s.put("skill",Map.of("key","analysis","version","1.0.0","installationId","install","packageSha256",HASH,"manifestSha256",HASH,"runner","native-model","entrypoint","SKILL.md"));
        s.put("taskId","task");s.put("conversationId","42");s.put("conversationGeneration","1");s.put("targetAgentId","agent");s.put("actionEventId","action");s.put("instruction","识别资料");s.put("parameters",Map.of());s.put("inputs",List.of());
        s.put("sourceSnapshotDigest",sha("[]"));s.put("outputs",outputs);s.put("taskGrant",Map.of("id","grant","version",1,"assignmentRevision",7));s.put("providerBinding",null);s.put("costInfo",Map.of("mode","UNKNOWN"));return s;
    }
    SkillExecutionService.EffectStart start(){return new SkillExecutionService.EffectStart("exec",skill.getSnapshotSha256(),fence,HASH,null);}
    SkillExecutionService.EffectResult effect(String status,String id,String result){return new SkillExecutionService.EffectResult("exec",skill.getSnapshotSha256(),fence,HASH,null,status,id,result);}
    SkillExecutionService.ResultCommit dataResult(){return new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","识别结果"),List.of(),List.of());}
    @Test void effectIsNewOnceAndReplayDoesNotRecheckProviderOrInstallation() {
        assertEquals("NEW",service.startEffect(runtime,"task","run","generate",start()).disposition());
        clearInvocations(installed);assertEquals("RECORDED",service.startEffect(runtime,"task","run","generate",start()).disposition());verifyNoInteractions(installed);
        assertEquals("WAITING_PROVIDER",skill.getPhase());assertEquals(1L,skill.getVersion());
    }
    @Test void effectDigestChangeCannotReopen(){service.startEffect(runtime,"task","run","generate",start());assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",new SkillExecutionService.EffectStart("exec",skill.getSnapshotSha256(),fence,"b".repeat(64),null)));}
    @Test void newEffectRequiresActualCatalogReadiness(){when(capabilities.lookup(any(),any())).thenReturn(SkillCapabilityLookup.View.unavailable(SkillCapabilityLookup.State.DISCONNECTED));assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",start()));verify(attachments,never()).compareAndSet(any(),anyLong(),anyString());}
    @Test void bindingRevisionCannotBeInvented(){assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",new SkillExecutionService.EffectStart("exec",skill.getSnapshotSha256(),fence,HASH,"invented")));}
    @Test void expiredAndWrongLeaseRejectEvenReplays(){service.startEffect(runtime,"task","run","generate",start());execution.setConversationLeaseExpiresAt(1L);assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",start()));}
    @Test void resumeTransformsLostStartReceiptToUnknownWithoutExecutionWork(){service.startEffect(runtime,"task","run","generate",start());var resume=service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));assertEquals("RESULT_UNKNOWN",resume.phase());assertFalse(resume.allowedWork().contains("EXECUTE"));assertEquals("UNKNOWN",service.startEffect(runtime,"task","run","generate",start()).disposition());}
    @Test void resultUnknownIsNotReportedAsDeterministicFailure(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("UNKNOWN",null,null));assertEquals("RESULT_UNKNOWN",skill.getPhase());assertEquals("QUEUED",execution.getExecutionState());}
    @Test void resultCannotCommitWhileAnEffectIsUnknown(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("UNKNOWN",null,null));assertThrows(Failure.class,()->service.commitResult(runtime,"task","run","manifest",dataResult()));}
    @Test void succeededEffectCannotChangeItsReceipt(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));assertThrows(Failure.class,()->service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","changed",HASH)));}
    @Test void providerRequestIdCannotBeRebound(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("ACCEPTED","request",null));assertThrows(Failure.class,()->service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","other",HASH)));}
    @Test void dataOnlyResultCommitsWithoutFakeAssetAndProjectionCompletesIt(){
        service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));
        var receipt=service.commitResult(runtime,"task","run","manifest",dataResult());assertEquals("RESULT_COMMITTED",receipt.state());assertEquals("ARCHIVING",receipt.phase());assertEquals("ARCHIVING",skill.getPhase());
        verify(pwe,never()).insertOutput(any());assertEquals("RESULT_COMMITTED",execution.getExecutionState());
        service.markProjected(owner,"exec",receipt.resultDigest());assertEquals("COMPLETE",skill.getPhase());service.markProjected(owner,"exec",receipt.resultDigest());
    }
    @Test void resultReplayDoesNotInvokeRunnerOrStorageAndDifferentDataConflicts(){
        service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));
        var first=service.commitResult(runtime,"task","run","manifest",dataResult());clearInvocations(storage,installed);
        assertEquals(first,service.commitResult(runtime,"task","run","manifest",dataResult()));verifyNoInteractions(storage,installed);
        assertThrows(Failure.class,()->service.commitResult(runtime,"task","run","manifest",new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","changed"),List.of(),List.of())));
    }
    @Test void structuredDataSchemaIsRealNotMarker(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));assertThrows(RuntimeException.class,()->service.commitResult(runtime,"task","run","manifest",new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer",7),List.of(),List.of())));}
    @Test void failedSourceSnapshotAndWrongSkillDigestReject(){skill.setSnapshotSha256("b".repeat(64));assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",start()));}
    @Test void projectionRequiresCommittedMacroState(){assertThrows(Failure.class,()->service.markProjected(owner,"exec",HASH));}
    @Test void resultReadNeedsCurrentConversationAcl(){when(conversations.requireAccessible(any(),eq("42"))).thenReturn(null);assertThrows(Failure.class,()->service.result(owner,"exec"));}
    @Test void skillCreationPinsAuthorityAndChargesNothing(){
        doAnswer(i->{execution=i.getArgument(0);return null;}).when(pwe).insert(any());
        doAnswer(i->{skill=i.getArgument(0);skill.setPhase("READY").setVersion(0L).setEffectsJson("{}");return null;}).when(attachments).insertReady(any());
        var result=service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot()));
        assertTrue(result.executionId().matches("pwe_[a-f0-9]{60}"));assertTrue(result.runId().matches("pwe_run_[a-f0-9]{64}"));assertEquals("READY",skill.getPhase());assertNull(execution.getControlledConsentId());assertEquals(4,execution.getExecutionProtocolVersion());assertNull(execution.getOutputContentMimeType());
        verify(grants).admit(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),eq("EXECUTE_SKILL"),eq(false));
    }
    @Test void mismatchedGrantSnapshotCannotCreate(){var snapshot=snapshot();snapshot.put("taskGrant",Map.of("id","other","version",1,"assignmentRevision",7));assertThrows(Failure.class,()->service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot)));verify(pwe,never()).insert(any());}
    @Test void malformedParametersCannotCreate(){var snapshot=snapshot();snapshot.put("parameters",Map.of("unknown",true));assertThrows(RuntimeException.class,()->service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot)));verify(pwe,never()).insert(any());}
    void replaceSnapshot(Map<String,Object> snapshot) {skill.setSnapshotJson(json(snapshot)).setSnapshotSha256(sha(json(snapshot)));}
    @Test void resultStoresExactCarrierEvidenceAndRejectsMissingReceipt() {
        var snapshot=snapshot();snapshot.put("inputs",List.of(Map.of("inputRef","input_1","sha256",HASH)));replaceSnapshot(snapshot);
        assertThrows(Failure.class,()->service.commitResult(runtime,"task","run","manifest",dataResult()));
        var receipt=new SkillExecutionService.InputReceipt("input_1",HASH,"native-image","DELIVERED");
        var result=service.commitResult(runtime,"task","run","manifest",new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","bird"),List.of(),List.of(receipt)));
        assertEquals("RESULT_COMMITTED",result.state());assertTrue(skill.getResultManifestJson().contains("native-image"));
    }
    @Test void wrongReceiptHashCarrierAndDuplicatesReject() {
        var snapshot=snapshot();snapshot.put("inputs",List.of(Map.of("inputRef","input_1","sha256",HASH)));replaceSnapshot(snapshot);
        for(var receipt:List.of(new SkillExecutionService.InputReceipt("input_1","b".repeat(64),"native-image","DELIVERED"),new SkillExecutionService.InputReceipt("input_1",HASH,"model-said-so","DELIVERED"),new SkillExecutionService.InputReceipt("input_1",HASH,"native-image","CLAIMED")))
            assertThrows(Failure.class,()->service.commitResult(runtime,"task","run","manifest",new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","bird"),List.of(),List.of(receipt))));
        verify(pwe,never()).update(any());
    }
    @Test void scalarAndArrayDataFollowDeclaredSchema() {
        outputs.put("dataSchema",Map.of("$schema",SkillManifestCodec.DIALECT,"type","array","items",Map.of("type","string")));replaceSnapshot(snapshot());
        var result=service.commitResult(runtime,"task","run","manifest",new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),List.of("bird"),List.of(),List.of()));
        assertEquals("RESULT_COMMITTED",result.state());assertTrue(skill.getResultManifestJson().contains("[\"bird\"]"));
    }
    @Test void unknownEffectFailureDoesNotInventDefinitiveFailure() {
        service.startEffect(runtime,"task","run","generate",start());
        var result=service.failConversation(runtime,"task","run",fence,"AGENT_DELIVERY_FAILED");
        assertEquals("QUEUED",result.state());assertEquals("RESULT_UNKNOWN",skill.getPhase());assertEquals("SKILL_EFFECT_RESULT_UNKNOWN",skill.getLastErrorCode());
    }
    @Test void definitiveFailureUpdatesBothRows() {
        service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("FAILED","request",null));
        var result=service.failConversation(runtime,"task","run",fence,"AGENT_DELIVERY_FAILED");assertEquals("FAILED",result.state());assertEquals("FAILED",skill.getPhase());
    }

}
