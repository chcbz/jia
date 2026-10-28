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
    final SkillManagementService management=mock(SkillManagementService.class);
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
        wireAssigneeFixture(service,pwe);
        service.setSkillExecutionDependencies(attachments,installed,capabilities,policy);
        service.setSkillManagement(management);
        // This suite isolates the actual PWE consumer. SQL lock serialization is covered by
        // SkillManagementTransactionTest, not simulated by these mocked repositories.
        when(management.admitNative(any(),anyString(),anyString(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(3)).get());
        when(management.nativeTaskBinding(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString())).thenAnswer(i->
            SkillManagementCodec.map("executionId",i.getArgument(2),"taskGrantId",i.getArgument(3),"taskGrantVersion",Long.toString(i.getArgument(4,Long.class)),
                "agentId",i.getArgument(5),"skillKey","analysis","skillInstallationId",i.getArgument(6),"productVersionId","spv_analysis_1",
                "skillVersion","1.0.0","packageSha256",HASH,"manifestSha256",HASH,"approvedPermissionsSha256",HASH));
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
        outputs.put("dataSchema",dataSchema);outputs.put("mimeTypes",List.of("image/png","text/plain"));outputs.put("multiple",true);outputs.put("artifactsRequired",false);
        var manifest=new LinkedHashMap<String,Object>();manifest.put("runner","native-model");manifest.put("entrypoint","SKILL.md");manifest.put("requirements",Map.of());manifest.put("outputs",outputs);
        manifest.put("inputs",Map.of("minItems",0,"maxItems",32,"acceptedMimeTypes",List.of("image/png")));manifest.put("parametersSchema",Map.of("$schema",SkillManifestCodec.DIALECT,"type","object","additionalProperties",false,"properties",Map.of()));
        when(installed.read(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(new InstalledSkillDescriptorLookup.Descriptor("analysis","1.0.0","install",HASH,HASH,json(manifest),"rti_"+"1".repeat(32),"host",null))));
        var sessionFence=new AgentManagedSessionLookup.SessionFence("rti_"+"1".repeat(32),"host","runtime",2);
        when(policy.read(any(),eq("task"))).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,7,sessionFence,new SkillCapabilityLookup.Scope("0","client","owner","agent")));
        when(capabilities.lookup(any(),eq(sessionFence))).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",sessionFence,new SkillCapabilityLookup.Catalog("1",List.of(new SkillCapabilityLookup.RuntimeSkill("analysis","1.0.0",HASH,HASH,"native-model",SkillCapabilityLookup.Readiness.READY,null,HASH,null,"install","spv_analysis_1","EXECUTABLE","1",true,1L)))));
        var snapshot=snapshot();String digest=sha(json(snapshot));
        execution=new PersonalWorkspaceExecutionEntity().setExecutionId("exec").setTaskId("task").setRunId("run").setConversationId("42").setTargetAgentId("agent").setOwnerJiacn("owner")
                .setExecutionMode("CONVERSATION").setExecutionState("QUEUED").setExecutionProtocolVersion(5).setPermittedOperation("EXECUTE_SKILL").setTaskGrantId("grant").setTaskGrantVersion(1L).setAssignmentRevision(7L)
                .setGrantRevision(1L).setCreatedAt(1L).setConversationLeaseVersion(1L).setConversationLeaseToken(fence.token()).setConversationLeaseRuntimeId("runtime").setConversationLeaseExpiresAt(System.currentTimeMillis()+900000);
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
        var s=new LinkedHashMap<String,Object>();s.put("schemaVersion",1);s.put("executionProtocolVersion",5);s.put("toolPolicyRef",SkillExecutionService.NATIVE_LOCAL_FILES_POLICY);s.put("skills",List.of(Map.of("skill",Map.of("key","analysis","version","1.0.0","installationId","install","packageSha256",HASH,"manifestSha256",HASH,"runner","native-model","entrypoint","SKILL.md"))));
        s.put("taskId","task");s.put("conversationId","42");s.put("conversationGeneration","1");s.put("targetAgentId","agent");s.put("actionEventId","action");s.put("instruction","识别资料");s.put("parameters",Map.of());s.put("inputs",List.of());
        s.put("sourceSnapshotDigest",sha("[]"));s.put("outputs",outputs);s.put("taskGrant",Map.of("id","grant","version",1,"assignmentRevision",7));s.put("providerBinding",null);s.put("costInfo",Map.of("mode","UNKNOWN"));return s;
    }
    SkillExecutionService.EffectStart start(){return new SkillExecutionService.EffectStart("exec",skill.getSnapshotSha256(),fence,HASH,null);}
    SkillExecutionService.EffectResult effect(String status,String id,String result){return new SkillExecutionService.EffectResult("exec",skill.getSnapshotSha256(),fence,HASH,null,status,id,result);}
    SkillExecutionService.ResultCommit dataResult(){return new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","识别结果"),List.of(),List.of());}
    @Test void delegatedRuntimeUsesProviderIdentityAndTaskOwnersDataThroughResultCommit() throws Exception {
        var provider = new RuntimeScope("0", "client", "provider", "agent", "runtime");
        var principal = org.springframework.beans.BeanUtils.instantiateClass(
                AgentRuntimeAuthentication.class.getDeclaredConstructor(AgentRuntimeAuthentication.Scope.class),
                new AgentRuntimeAuthentication.Scope("0", "client", "provider", "agent", "runtime"));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(),
                "rti_"+"1".repeat(32), "host", 2, "b".repeat(64), 8, 2));
        SecurityContextHolder.getContext().setAuthentication(principal);
        var assignees = mock(TaskAssigneeAuthorityService.class);
        var target = new TaskAssigneeAuthorityService.Target("0", "client", "provider", "agent", 2, 1L);
        when(assignees.resolveForAssignment("0", "client", "owner", "agent")).thenReturn(target);
        when(assignees.requireGrantTarget("0", "client", "owner", "task", "grant", 1)).thenReturn(target);
        service.setAssigneeAuthority(assignees);
        when(pwe.findForRuntime("0", "client", "provider", "agent", "task", "run")).thenReturn(execution);
        var session = new AgentManagedSessionLookup.SessionFence("rti_"+"1".repeat(32), "host", "runtime", 2);
        var providerCatalog = new SkillCapabilityLookup.Scope("0", "client", "provider", "agent");
        when(policy.read(any(), eq("task"))).thenReturn(new TaskSkillPolicyService.Snapshot(true, null, 7, session, providerCatalog));
        assertEquals("NEW", service.startEffect(provider, "task", "run", "generate", start()).disposition());
        service.recordEffect(provider, "task", "run", "generate", effect("SUCCEEDED", "request", HASH));
        var receipt = commit(provider, dataResult());
        assertEquals("RESULT_COMMITTED", receipt.state());
        assertEquals("owner", execution.getOwnerJiacn());
        assertEquals("owner", skill.getOwnerJiacn());
        verify(installed, atLeastOnce()).read(providerCatalog);
        // The task is already admitted with its exact descriptor. A new catalog may no longer
        // expose this installation after upgrade; it must not revoke the frozen task reference.
        verifyNoInteractions(capabilities);
        verify(capabilities, never()).lookup(new SkillCapabilityLookup.Scope("0", "client", "owner", "agent"), session);
        verify(attachments, never()).lock("0", "client", "provider", "exec");
        verify(assignees, atLeastOnce()).requireGrantTarget("0", "client", "owner", "task", "grant", 1);
        // The task owner cannot impersonate the provider even though it owns the task data.
        assertThrows(Failure.class, () -> service.resume(runtime, "task", "run",
                new SkillExecutionService.Resume("exec", skill.getSnapshotSha256())));
        assertThrows(Failure.class, () -> service.resume(new RuntimeScope("0", "foreign", "provider", "agent", "runtime"),
                "task", "run", new SkillExecutionService.Resume("exec", skill.getSnapshotSha256())));
    }

    @Test void effectIsNewOnceAndReplayDoesNotRecheckProviderOrInstallation() {
        assertEquals("NEW",service.startEffect(runtime,"task","run","generate",start()).disposition());
        clearInvocations(installed);assertEquals("RECORDED",service.startEffect(runtime,"task","run","generate",start()).disposition());verifyNoInteractions(installed);
        assertEquals("WAITING_PROVIDER",skill.getPhase());assertEquals(1L,skill.getVersion());
    }
    @Test void effectDigestChangeCannotReopen(){service.startEffect(runtime,"task","run","generate",start());assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",new SkillExecutionService.EffectStart("exec",skill.getSnapshotSha256(),fence,"b".repeat(64),null)));}
    @Test void alreadyAdmittedOldInstallationDoesNotBorrowCurrentCatalogReadiness(){
        when(capabilities.lookup(any(),any())).thenReturn(SkillCapabilityLookup.View.unavailable(SkillCapabilityLookup.State.DISCONNECTED));
        assertEquals("NEW",service.startEffect(runtime,"task","run","generate",start()).disposition());verifyNoInteractions(capabilities);
    }
    @Test void oldTaskStillRequiresItsExactAuthorizedDescriptorEvenIfCurrentCatalogIsReady(){
        when(installed.read(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of()));
        assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",start()));
        verify(attachments,never()).compareAndSet(any(),anyLong(),anyString());
    }
    @Test void bindingRevisionCannotBeInvented(){assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",new SkillExecutionService.EffectStart("exec",skill.getSnapshotSha256(),fence,HASH,"invented")));}
    @Test void expiredAndWrongLeaseRejectEvenReplays(){service.startEffect(runtime,"task","run","generate",start());execution.setConversationLeaseExpiresAt(1L);assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",start()));}
    @Test void resumeDoesNotInventUnknownForAnUnconfirmedStart() {
        service.startEffect(runtime,"task","run","generate",start());String before=skill.getEffectsJson();
        var resume=service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertEquals("WAITING_PROVIDER",resume.phase());assertFalse(resume.allowedWork().contains("EXECUTE"));
        assertEquals("START_RECORDED",((Map<?,?>)resume.effects().get("generate")).get("status"));
        assertFalse(((Map<?,?>)resume.effects().get("generate")).containsKey("ownerRuntimeFence"));
        assertEquals(before,skill.getEffectsJson());assertEquals("RECORDED",service.startEffect(runtime,"task","run","generate",start()).disposition());
    }
    @Test void resultUnknownIsNotReportedAsDeterministicFailure(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("UNKNOWN",null,null));assertEquals("RESULT_UNKNOWN",skill.getPhase());assertEquals("QUEUED",execution.getExecutionState());}
    @Test void resultCannotCommitWhileAnEffectIsUnknown(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("UNKNOWN",null,null));assertThrows(Failure.class,()->commit(runtime,dataResult()));}
    @Test void succeededEffectCannotChangeItsReceipt(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));assertThrows(Failure.class,()->service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","changed",HASH)));}
    @Test void providerRequestIdCannotBeRebound(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("ACCEPTED","request",null));assertThrows(Failure.class,()->service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","other",HASH)));}
    @Test void dataOnlyResultCommitsWithoutFakeAssetAndProjectionCompletesIt(){
        service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));
        var receipt=commit(runtime,dataResult());assertEquals("RESULT_COMMITTED",receipt.state());assertEquals("ARCHIVING",receipt.phase());assertEquals("ARCHIVING",skill.getPhase());
        verify(pwe,never()).insertOutput(any());assertEquals("RESULT_COMMITTED",execution.getExecutionState());
        service.markProjected(owner,"exec",receipt.resultDigest());assertEquals("COMPLETE",skill.getPhase());service.markProjected(owner,"exec",receipt.resultDigest());
    }
    @Test void resultReplayDoesNotInvokeRunnerOrStorageAndDifferentDataConflicts(){
        service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));
        var first=commit(runtime,dataResult());clearInvocations(storage,installed);
        assertEquals(first,commit(runtime,dataResult()));verifyNoInteractions(storage,installed);
        assertThrows(Failure.class,()->commit(runtime,new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","changed"),List.of(),List.of())));
    }
    @Test void structuredDataSchemaIsRealNotMarker(){service.startEffect(runtime,"task","run","generate",start());service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));assertThrows(RuntimeException.class,()->commit(runtime,new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer",7),List.of(),List.of())));}
    @Test void failedSourceSnapshotAndWrongSkillDigestReject(){skill.setSnapshotSha256("b".repeat(64));assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","generate",start()));}
    @Test void projectionRequiresCommittedMacroState(){assertThrows(Failure.class,()->service.markProjected(owner,"exec",HASH));}
    @Test void resultReadNeedsCurrentConversationAcl(){when(conversations.requireAccessible(any(),eq("42"))).thenReturn(null);assertThrows(Failure.class,()->service.result(owner,"exec"));}
    @Test void skillCreationPinsAuthorityAndChargesNothing(){
        doAnswer(i->{execution=i.getArgument(0);return null;}).when(pwe).insert(any());
        doAnswer(i->{skill=i.getArgument(0);skill.setPhase("READY").setVersion(0L).setEffectsJson("{}");return null;}).when(attachments).insertReady(any());
        var result=service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot()));
        assertTrue(result.executionId().matches("pwe_[a-f0-9]{60}"));assertTrue(result.runId().matches("pwe_run_[a-f0-9]{64}"));assertEquals("READY",skill.getPhase());assertNull(execution.getControlledConsentId());assertEquals(5,execution.getExecutionProtocolVersion());assertNull(execution.getOutputContentMimeType());
        verify(grants).admit(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),eq("EXECUTE_SKILL"),eq(false));
        verify(management).admitNative(eq(new SkillCapabilityLookup.Scope("0","client","owner","agent")),eq("analysis"),eq("install"),any());
        var stored=JsonMapper.builder().build().readValue(skill.getTaskSkillBindingJson(),List.class);
        var binding=(Map<?,?>)((Map<?,?>)stored.getFirst()).get("taskSkillBinding");
        assertNull(skill.getSkillKey());assertNull(skill.getSkillVersion());assertNull(skill.getPackageSha256());assertNull(skill.getManifestSha256());
        assertEquals(result.executionId(),binding.get("executionId"));assertEquals("install",binding.get("skillInstallationId"));
        assertEquals("grant",binding.get("taskGrantId"));assertEquals("1",binding.get("taskGrantVersion"));
        assertFalse(skill.getSnapshotJson().contains("taskSkillBinding"));assertEquals(sha(skill.getSnapshotJson()),skill.getSnapshotSha256());
    }
    @Test void revokedOrMissingTaskGrantCannotAdmitNativeExecution() {
        when(grants.admit(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),eq("EXECUTE_SKILL"),eq(false)))
                .thenThrow(new AgentTaskExecutionGrantException(AgentTaskExecutionGrantException.Reason.NOT_FOUND,"unavailable"));
        assertThrows(RuntimeException.class,()->service.create(owner,
                new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot())));
        verify(pwe,never()).insert(any());verify(attachments,never()).insertReady(any());
    }
    @Test void mismatchedGrantSnapshotCannotCreate(){var snapshot=snapshot();snapshot.put("taskGrant",Map.of("id","other","version",1,"assignmentRevision",7));assertThrows(Failure.class,()->service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot)));verify(pwe,never()).insert(any());}
    @Test void malformedParametersCannotCreate(){var snapshot=snapshot();snapshot.put("parameters",Map.of("unknown",true));assertThrows(RuntimeException.class,()->service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot)));verify(pwe,never()).insert(any());}
    void replaceSnapshot(Map<String,Object> snapshot) {skill.setSnapshotJson(json(snapshot)).setSnapshotSha256(sha(json(snapshot)));}
    @Test void resultStoresExactCarrierEvidenceAndRejectsMissingReceipt() {
        var snapshot=snapshot();snapshot.put("inputs",List.of(Map.of("inputRef","input_1","sha256",HASH)));replaceSnapshot(snapshot);
        assertThrows(Failure.class,()->commit(runtime,dataResult()));
        var receipt=new SkillExecutionService.InputReceipt("input_1",HASH,"native-image","DELIVERED");
        var result=commit(runtime,new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","bird"),List.of(),List.of(receipt)));
        assertEquals("RESULT_COMMITTED",result.state());assertTrue(skill.getResultManifestJson().contains("native-image"));
    }
    @Test void wrongReceiptHashCarrierAndDuplicatesReject() {
        var snapshot=snapshot();snapshot.put("inputs",List.of(Map.of("inputRef","input_1","sha256",HASH)));replaceSnapshot(snapshot);
        for(var receipt:List.of(new SkillExecutionService.InputReceipt("input_1","b".repeat(64),"native-image","DELIVERED"),new SkillExecutionService.InputReceipt("input_1",HASH,"model-said-so","DELIVERED"),new SkillExecutionService.InputReceipt("input_1",HASH,"native-image","CLAIMED")))
            assertThrows(Failure.class,()->commit(runtime,new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","bird"),List.of(),List.of(receipt))));
        verify(pwe,never()).update(any());
    }
    @Test void scalarAndArrayDataFollowDeclaredSchema() {
        outputs.put("dataSchema",Map.of("$schema",SkillManifestCodec.DIALECT,"type","array","items",Map.of("type","string")));replaceSnapshot(snapshot());
        var result=commit(runtime,new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),List.of("bird"),List.of(),List.of()));
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

    @Test void nativeTurnFailureAndUnknownEffectAreBothRetainedUntilRealResolution() {
        service.startEffect(runtime,"task","run","generate",start());
        var result=service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED");
        assertEquals("QUEUED",result.state());assertEquals("RESULT_UNKNOWN",skill.getPhase());assertEquals("NATIVE_EXECUTION_TURN_FAILED",skill.getLastErrorCode());
        long version=skill.getVersion();service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED");assertEquals(version,skill.getVersion());
        assertThrows(Failure.class,()->service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_INTERRUPTED"));
        var resume=service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertEquals(List.of("QUERY_EFFECT"),resume.allowedWork());
        assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","replacement",start()));
        assertThrows(Failure.class,()->commit(runtime,dataResult()));
        service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH));
        assertEquals("FAILED",execution.getExecutionState());assertEquals("FAILED",skill.getPhase());assertEquals("NATIVE_EXECUTION_TURN_FAILED",skill.getLastErrorCode());
        assertEquals("KNOWN",service.recordEffect(runtime,"task","run","generate",effect("SUCCEEDED","request",HASH)).disposition());
        assertEquals("FAILED",service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED").state());
    }
    @Test void knownInterruptedWithoutUnknownEffectIsTerminal() {
        assertEquals("FAILED",service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_INTERRUPTED").state());
        assertEquals("FAILED",skill.getPhase());assertEquals("NATIVE_EXECUTION_TURN_INTERRUPTED",skill.getLastErrorCode());
        assertThrows(Failure.class,()->service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED"));
    }
    RuntimeScope authenticatedRestart() throws Exception {
        var scope=new RuntimeScope("0","client","owner","agent","runtime-new");
        var principal=org.springframework.beans.BeanUtils.instantiateClass(AgentRuntimeAuthentication.class.getDeclaredConstructor(AgentRuntimeAuthentication.Scope.class),
                new AgentRuntimeAuthentication.Scope("0","client","owner","agent","runtime-new"));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(),"rti_"+"1".repeat(32),"host",2,"b".repeat(64),8,2));
        SecurityContextHolder.getContext().setAuthentication(principal);return scope;
    }
    @Test void lostFailureResponseIsReadableByRestartWithoutNewLeaseOrScriptCapability() throws Exception {
        var snapshot=snapshot();snapshot.put("toolPolicyRef",SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY);replaceSnapshot(snapshot);
        service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED");
        var restarted=authenticatedRestart();clearInvocations(pwe,attachments);
        var receipt=service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertNull(receipt.lease());assertEquals("FAILED",receipt.phase());assertEquals(List.of(),receipt.allowedWork());
        assertNull(receipt.resultManifest());assertEquals("NATIVE_EXECUTION_TURN_FAILED",receipt.turnFailureCode());
        verify(pwe,never()).update(any());verify(attachments,never()).compareAndSet(any(),anyLong(),anyString());
        assertThrows(Failure.class,()->service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec","b".repeat(64))));
        assertThrows(Failure.class,()->service.failConversation(restarted,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED"));
        execution.setConversationLeaseExpiresAt(1L);
        assertEquals("FAILED",service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())).phase());
    }
    @Test void expiredLeaseAllowsRestartToReportLocallyPersistedFailureButOldFenceRemainsInvalid() throws Exception {
        emptySkills();execution.setConversationLeaseExpiresAt(1L);var restarted=authenticatedRestart();
        assertThrows(Failure.class,()->service.failConversation(restarted,"task","run",fence,"NATIVE_EXECUTION_TURN_INTERRUPTED"));
        var receipt=service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertNull(receipt.turnFailureCode());assertEquals(2L,receipt.lease().version());
        assertThrows(Failure.class,()->service.failConversation(restarted,"task","run",fence,"NATIVE_EXECUTION_TURN_INTERRUPTED"));
        var renewed=new ConversationFence(receipt.lease().version(),receipt.lease().token());
        assertEquals("FAILED",service.failConversation(restarted,"task","run",renewed,"NATIVE_EXECUTION_TURN_INTERRUPTED").state());
        var confirmation=service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertEquals("NATIVE_EXECUTION_TURN_INTERRUPTED",confirmation.turnFailureCode());assertNull(confirmation.lease());
    }
    @Test void recordedTurnFailureAndUnknownEffectRecoverAfterLeaseExpiryWithoutScriptCapability() throws Exception {
        service.startEffect(runtime,"task","run","generate",start());
        service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED");
        var snapshot=snapshot();snapshot.put("toolPolicyRef",SkillExecutionService.NATIVE_SKILL_SCRIPT_POLICY);replaceSnapshot(snapshot);
        var restarted=authenticatedRestart();
        assertThrows(Failure.class,()->service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())));
        execution.setConversationLeaseExpiresAt(1L);
        var receipt=service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertEquals("NATIVE_EXECUTION_TURN_FAILED",receipt.turnFailureCode());assertEquals(List.of("QUERY_EFFECT"),receipt.allowedWork());
        assertEquals("START_RECORDED",((Map<?,?>)receipt.effects().get("generate")).get("status"));
        var renewed=new ConversationFence(receipt.lease().version(),receipt.lease().token());
        long version=skill.getVersion();
        assertEquals("QUEUED",service.failConversation(restarted,"task","run",renewed,"NATIVE_EXECUTION_TURN_FAILED").state());
        assertEquals("RESULT_UNKNOWN",skill.getPhase());assertEquals(version,skill.getVersion());
        assertThrows(Failure.class,()->service.failConversation(restarted,"task","run",renewed,"NATIVE_EXECUTION_TURN_INTERRUPTED"));
        assertThrows(Failure.class,()->service.failConversation(restarted,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED"));
        service.recordEffect(restarted,"task","run","generate",new SkillExecutionService.EffectResult("exec",skill.getSnapshotSha256(),renewed,HASH,null,"FAILED",null,null));
        assertEquals("FAILED",service.failConversation(restarted,"task","run",renewed,"NATIVE_EXECUTION_TURN_FAILED").state());
        assertEquals("FAILED",service.resume(restarted,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())).phase());
    }
    @Test void newEffectPreparesBeforeNativeTransactionButReplaysNeverReprepare() {
        var auth=mock(AgentRuntimeAuthenticationService.class);var observed=new ArrayList<Boolean>();
        var descriptors=installed.read(new SkillCapabilityLookup.Scope("0","client","owner","agent"));
        when(installed.read(any())).thenAnswer(i->{observed.add(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return descriptors;});
        when(auth.withNativeFence(any(),any())).thenAnswer(i->{
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try{return ((Supplier<?>)i.getArgument(1)).get();}
            finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        });service.setRuntimeAuthentication(auth);
        assertEquals("NEW",service.startEffect(runtime,"task","run","generate",start()).disposition());
        assertEquals(List.of(false,true),observed);
        observed.clear();doThrow(new InstalledSkillDescriptorLookup.SourceUnavailable()).when(installed).read(any());
        assertEquals("RECORDED",service.startEffect(runtime,"task","run","generate",start()).disposition());
        assertTrue(observed.isEmpty());
    }
    @Test void nativeLocalFileReceiptDoesNotRequireALocalScriptRunner() {
        var input=new LinkedHashMap<String,Object>();input.put("inputRef","source");input.put("sourceKind","TASK_LINKED_WORKSPACE_VERSION");input.put("sha256",HASH);input.put("byteLength",1);input.put("contentMimeType","image/png");input.put("sourceIdentity",Map.of("fileId","file","version",1,"purpose","REFERENCE"));
        var snap=snapshot();snap.put("inputs",List.of(input));snap.put("sourceSnapshotDigest",sha(json(List.of(input))));skill.setSnapshotJson(json(snap)).setSnapshotSha256(sha(json(snap)));
        assertEquals("RESULT_COMMITTED",commit(runtime,new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","bird"),List.of(),List.of(new SkillExecutionService.InputReceipt("source",HASH,"local-file","DELIVERED")))).state());
    }

    SkillExecutionService.ResultReceipt commit(RuntimeScope scope,SkillExecutionService.ResultCommit command) {
        return service.commitResult(scope,"task","run",manifestId(command),command);
    }
    static String manifestId(SkillExecutionService.ResultCommit command) {
        var preimage=new LinkedHashMap<String,Object>();preimage.put("schemaVersion",2);preimage.put("data",command.data());
        preimage.put("artifacts",command.artifacts().stream().map(a->Map.of("outputId",a.outputId(),"sha256",a.sha256(),"byteLength",a.byteLength())).toList());
        preimage.put("inputReceipts",command.inputReceipts().stream().map(r->Map.of("inputRef",r.inputRef(),"sha256",r.sha256(),"carrier",r.carrier(),"delivery",r.delivery())).toList());
        preimage.put("effectDependencies",command.effectDependencies());return "manifest_"+sha(SkillCanonicalJson.write(preimage));
    }
    void emptySkills() {var snapshot=snapshot();snapshot.put("skills",List.of());replaceSnapshot(snapshot);skill.setTaskSkillBindingJson("[]");}
    void insertFixtures() {
        when(pwe.findByTaskRun(eq("0"),eq("client"),eq("owner"),eq("task"),anyString())).thenAnswer(i->execution);
        when(pwe.lockByTaskRun(eq("0"),eq("client"),eq("owner"),eq("task"),anyString())).thenAnswer(i->execution);
        doAnswer(i->{execution=i.getArgument(0);return null;}).when(pwe).insert(any());
        doAnswer(i->{skill=i.getArgument(0);skill.setPhase("READY").setVersion(0L).setEffectsJson("{}");return null;}).when(attachments).insertReady(any());
    }
    @Test void emptySkillsAdmitWithoutFakeInstallationOrCatalogAndFreezeOutputs() {
        var snapshot=snapshot();snapshot.put("skills",List.of());insertFixtures();clearInvocations(installed,management,capabilities,policy);
        var created=service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot));
        assertEquals(5,execution.getExecutionProtocolVersion());assertEquals("[]",skill.getTaskSkillBindingJson());assertNull(skill.getSkillKey());
        assertEquals(sha(json(snapshot)),created.snapshotDigest());verifyNoInteractions(installed,management,capabilities,policy);
    }
    @Test void emptySkillsCommandRetainsExplicitLocalPolicyInstructionParametersAndInputs() {
        emptySkills();skill.setPhase("READY");execution.setInstruction("write a real file");
        when(pwe.listQueuedConversationsByTarget(eq("0"),eq("client"),eq("owner"),eq("agent"),isNull(),isNull(),eq(16))).thenReturn(List.of(execution));
        var command=(SkillExecutionService.Command)service.runtimeConversationCommandViews(runtime,16).getFirst();
        assertEquals(5,command.executionProtocolVersion());assertEquals(List.of(),command.skills());
        assertEquals(SkillExecutionService.NATIVE_LOCAL_FILES_POLICY,command.toolPolicyRef());assertEquals("write a real file",command.instruction());
        assertEquals(Map.of(),command.parameters());assertEquals(List.of(),command.inputRefs());assertEquals(outputs,command.outputs());
        verifyNoInteractions(installed,management,capabilities,policy);
    }
    @Test void instructionPackageKeepsNullManifestAndAuthorizedOutputs() {
        var snap=snapshot();
        var selected=SkillManagementCodec.map("key","analysis","version","1.0.0","installationId","install","packageSha256",HASH,"manifestSha256",null);
        snap.put("skills",List.of(Map.of("skill",selected)));
        when(installed.read(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(
                new InstalledSkillDescriptorLookup.Descriptor("analysis","1.0.0","install",HASH,null,null,"rti_"+"1".repeat(32),"host",null))));
        var session=new AgentManagedSessionLookup.SessionFence("rti_"+"1".repeat(32),"host","runtime",2);
        var report=new SkillCapabilityLookup.RuntimeSkill("analysis","1.0.0",HASH,null,null,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE,
                null,null,null,"install","spv_analysis_1","INSTRUCTION","1",true,1L);
        when(capabilities.lookup(any(),eq(session))).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",session,new SkillCapabilityLookup.Catalog("1",List.of(report))));
        when(management.nativeTaskBinding(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString())).thenAnswer(i->
                SkillManagementCodec.map("executionId",i.getArgument(2),"taskGrantId",i.getArgument(3),"taskGrantVersion",Long.toString(i.getArgument(4,Long.class)),
                "agentId",i.getArgument(5),"skillKey","analysis","skillInstallationId",i.getArgument(6),"productVersionId","spv_analysis_1",
                "skillVersion","1.0.0","packageSha256",HASH,"manifestSha256",null,"approvedPermissionsSha256",HASH));
        insertFixtures();service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snap));
        when(pwe.listQueuedConversationsByTarget(eq("0"),eq("client"),eq("owner"),eq("agent"),isNull(),isNull(),eq(16))).thenReturn(List.of(execution));
        var command=(SkillExecutionService.Command)service.runtimeConversationCommandViews(runtime,16).getFirst();
        assertNull(command.skills().getFirst().skill().manifestSha256());
        assertTrue(command.skills().getFirst().taskSkillBinding().containsKey("manifestSha256"));
        assertNull(command.skills().getFirst().taskSkillBinding().get("manifestSha256"));
        assertEquals(outputs,command.outputs());
        var binding=new LinkedHashMap<>(command.skills().getFirst().taskSkillBinding());
        assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.validate("TaskSkillBinding",binding));
        assertDoesNotThrow(()->SkillManagementCodec.validateNativeTaskBinding(binding));
        binding.remove("approvedPermissionsSha256");assertThrows(SkillMarketplaceException.class,()->SkillManagementCodec.validateNativeTaskBinding(binding));
    }
    @Test void compatibleMultipleSkillsKeepTheirOwnExactBindingsAndAdmissionLocks() {
        var scope=new SkillCapabilityLookup.Scope("0","client","owner","agent");
        var first=installed.read(scope).skills().getFirst();
        var second=new InstalledSkillDescriptorLookup.Descriptor("format",first.skillVersion(),"install_2",
                first.packageSha256(),first.manifestSha256(),first.manifestJson(),first.runtimeInstallationId(),first.runtimeHostId(),null);
        when(installed.read(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(first,second)));
        var session=new AgentManagedSessionLookup.SessionFence("rti_"+"1".repeat(32),"host","runtime",2);
        var original=capabilities.lookup(scope,session).catalog().skills().getFirst();
        var extra=new SkillCapabilityLookup.RuntimeSkill("format","1.0.0",HASH,HASH,"native-model",SkillCapabilityLookup.Readiness.READY,
                null,HASH,null,"install_2","spv_analysis_1","EXECUTABLE","1",true,1L);
        when(capabilities.lookup(any(),eq(session))).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",session,
                new SkillCapabilityLookup.Catalog("1",List.of(original,extra))));
        when(management.nativeTaskBinding(anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString())).thenAnswer(i->
                SkillManagementCodec.map("executionId",i.getArgument(2),"taskGrantId",i.getArgument(3),"taskGrantVersion",Long.toString(i.getArgument(4,Long.class)),
                        "agentId",i.getArgument(5),"skillKey","install_2".equals(i.getArgument(6))?"format":"analysis","skillInstallationId",i.getArgument(6),
                        "productVersionId","spv_analysis_1","skillVersion","1.0.0","packageSha256",HASH,"manifestSha256",HASH,"approvedPermissionsSha256",HASH));
        var snap=snapshot();var selection=new LinkedHashMap<>((Map<String,Object>)((List<?>)snap.get("skills")).getFirst());
        var selected=new LinkedHashMap<>((Map<String,Object>)selection.get("skill"));selected.put("installationId","install_2");selected.put("key","format");selection.put("skill",selected);
        snap.put("skills",List.of(selection,((List<?>)snap.get("skills")).getFirst()));insertFixtures();
        service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snap));
        var order=inOrder(management);order.verify(management).admitNative(eq(scope),eq("analysis"),eq("install"),any());
        order.verify(management).admitNative(eq(scope),eq("format"),eq("install_2"),any());
        when(pwe.listQueuedConversationsByTarget(eq("0"),eq("client"),eq("owner"),eq("agent"),isNull(),isNull(),eq(16))).thenReturn(List.of(execution));
        var command=(SkillExecutionService.Command)service.runtimeConversationCommandViews(runtime,16).getFirst();
        assertEquals(2,command.skills().size());
        assertEquals("install_2",command.skills().getFirst().taskSkillBinding().get("skillInstallationId"));
        assertEquals("install",command.skills().get(1).taskSkillBinding().get("skillInstallationId"));
        assertTrue(command.skills().stream().allMatch(item->execution.getExecutionId().equals(item.taskSkillBinding().get("executionId"))));
    }
    @Test void mismatchedSkillBindingCannotBeDispatched() {
        insertFixtures();service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot()));
        skill.setTaskSkillBindingJson(skill.getTaskSkillBindingJson().replace("\"skillInstallationId\":\"install\"","\"skillInstallationId\":\"foreign\""));
        when(pwe.listQueuedConversationsByTarget(eq("0"),eq("client"),eq("owner"),eq("agent"),isNull(),isNull(),eq(16))).thenReturn(List.of(execution));
        assertTrue(service.runtimeConversationCommandViews(runtime,16).isEmpty());
    }
    @Test void duplicateSkillInstallationsAreRejectedRatherThanCollapsed() {
        var snap=snapshot();var item=((List<?>)snap.get("skills")).getFirst();snap.put("skills",List.of(item,item));
        assertThrows(Failure.class,()->service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snap)));
        verify(pwe,never()).insert(any());
    }
    @Test void nullOrDuplicateEffectDependenciesAreBadRequests() {
        var nulls=new ArrayList<String>();nulls.add(null);
        for(List<String> dependencies:List.<List<String>>of(nulls,List.of("same","same"))) {
            var command=new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),null,List.of(),List.of(),dependencies);
            assertEquals(Reason.BAD_REQUEST,assertThrows(Failure.class,()->service.commitResult(runtime,"task","run","manifest_"+HASH,command)).getReason());
        }
    }
    @Test void resultSchemaIsBoundToItsExecutionProtocolWithoutRewritingHistory() {
        emptySkills();commit(runtime,dataResult());
        assertEquals(2,service.result(owner,"exec").resultManifest().get("schemaVersion"));
        execution.setExecutionProtocolVersion(4);skill.setPhase("COMPLETE");
        assertEquals(Reason.TASK_CONFLICT,assertThrows(Failure.class,()->service.result(owner,"exec")).getReason());
    }
    @Test void requiredNativeArtifactCannotCommitAnEmptyResult() {
        outputs.clear();outputs.put("mimeTypes",List.of("text/plain"));outputs.put("multiple",true);outputs.put("artifactsRequired",true);emptySkills();
        var empty=new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),null,List.of(),List.of(),List.of());
        assertEquals(Reason.BAD_REQUEST,assertThrows(Failure.class,()->commit(runtime,empty)).getReason());
    }
    @Test void stagedNativeFileLostAckReusesBytesAndCommitRecoveryNeverRegenerates() {
        assertNativeFileRecovery("native result\n".getBytes(StandardCharsets.UTF_8));
    }
    @Test void emptyNativeTextArtifactIsNotConfusedWithMissingBytes() {
        assertNativeFileRecovery(new byte[0]);
    }
    void assertNativeFileRecovery(byte[] bytes) {
        outputs.clear();outputs.put("mimeTypes",List.of("text/plain"));outputs.put("multiple",false);outputs.put("artifactsRequired",true);emptySkills();
        String hash=sha(new String(bytes,StandardCharsets.UTF_8));
        var rows=new LinkedHashMap<String,PersonalWorkspaceExecutionOutputEntity>();
        when(pwe.lockOutput(eq("0"),eq("client"),eq("owner"),eq("exec"),anyString())).thenAnswer(i->rows.get(i.getArgument(4)));
        when(pwe.lockOutputs("0","client","owner","exec")).thenAnswer(i->new ArrayList<>(rows.values()));
        doAnswer(i->{var row=i.getArgument(0,PersonalWorkspaceExecutionOutputEntity.class);rows.put(row.getOutputId(),row);return null;}).when(pwe).insertOutput(any());
        when(storage.store(any(),any(),eq("text/plain"))).thenReturn(new PersonalWorkspaceStorage.StoredObject("private-object",hash,bytes.length,"text/plain"));
        when(storage.read(any(),eq("private-object"),eq(hash),eq((long)bytes.length),eq("text/plain")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"text/plain"));
        var first=service.stageConversationOutput(runtime,"task","run",fence,"output_native_1","result.txt","text/plain",bytes);
        assertEquals(first,service.stageConversationOutput(runtime,"task","run",fence,"output_native_1","result.txt","text/plain",bytes));
        verify(storage,times(1)).store(any(),any(),eq("text/plain"));verify(pwe,times(1)).insertOutput(any());
        var command=new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),null,
                List.of(new OutputDeclaration("output_native_1",hash,bytes.length)),List.of(),List.of());
        var receipt=commit(runtime,command);assertEquals("RESULT_COMMITTED",receipt.state());assertEquals("COMMITTED",rows.get("output_native_1").getOutputState());
        var manifest=JsonMapper.builder().build().readValue(skill.getResultManifestJson(),Map.class);
        assertEquals(2,manifest.get("schemaVersion"));assertEquals(bytes.length,((Map<?,?>)((List<?>)manifest.get("artifacts")).getFirst()).get("byteLength"));
        var recovery=service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertEquals(List.of("PROJECT_RESULT"),recovery.allowedWork());assertEquals(manifest,recovery.resultManifest());
        clearInvocations(storage,installed);assertEquals(receipt,commit(runtime,command));
        verify(storage).maxContentBytes();verifyNoMoreInteractions(storage);verifyNoInteractions(installed);
    }
    @Test void emptySkillsDoNotBypassCurrentConversationAclOrGeneration() {
        emptySkills();
        when(conversations.requireAccessible(any(),eq("42"))).thenReturn(null);
        assertThrows(Failure.class,()->service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())));
        assertThrows(Failure.class,()->commit(runtime,dataResult()));
        when(conversations.requireAccessible(any(),eq("42"))).thenReturn(new WorkspaceConversationAccessService.ConversationView("42","bounty","task:task","task",List.of("agent"),2,1));
        assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","new-step",start()));
        assertThrows(Failure.class,()->service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())));
        verify(pwe,never()).update(any());verify(attachments,never()).compareAndSet(any(),anyLong(),anyString());
    }
    @Test void finalResultCanExcludeKnownFailedExplorationButCannotDependOnIt() {
        emptySkills();service.startEffect(runtime,"task","run","explore",start());
        service.recordEffect(runtime,"task","run","explore",effect("FAILED",null,null));
        service.startEffect(runtime,"task","run","final",start());service.recordEffect(runtime,"task","run","final",effect("SUCCEEDED",null,HASH));
        var invalid=new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","ok"),List.of(),List.of(),List.of("explore"));
        assertThrows(Failure.class,()->commit(runtime,invalid));
        var valid=new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","ok"),List.of(),List.of(),List.of("final"));
        assertEquals("RESULT_COMMITTED",commit(runtime,valid).state());assertTrue(skill.getResultManifestJson().contains("effectDependencies"));
    }
    @Test void resumeAllowsOnlyNewAuthorizedStepsAfterConfirmedEffectsAndReplaysNeverReexecute() {
        emptySkills();service.startEffect(runtime,"task","run","first",start());service.recordEffect(runtime,"task","run","first",effect("SUCCEEDED",null,HASH));
        var resume=service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));
        assertTrue(resume.allowedWork().contains("CONTINUE_EXECUTION"));assertEquals("KNOWN",service.startEffect(runtime,"task","run","first",start()).disposition());
        assertEquals("NEW",service.startEffect(runtime,"task","run","second",start()).disposition());
    }
    @Test void unknownEffectCannotBeBypassedByNewIdOrOmittingDependencies() {
        emptySkills();service.startEffect(runtime,"task","run","unknown",start());service.recordEffect(runtime,"task","run","unknown",effect("UNKNOWN",null,null));
        assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","replacement",start()));
        assertThrows(Failure.class,()->commit(runtime,dataResult()));
        var resume=service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256()));assertFalse(resume.allowedWork().contains("CONTINUE_EXECUTION"));
    }
    @Test void nonexistentDependencyAndWrongManifestIdCannotCommit() {
        emptySkills();var missing=new SkillExecutionService.ResultCommit(fence,skill.getSnapshotSha256(),Map.of("answer","ok"),List.of(),List.of(),List.of("missing"));
        assertThrows(Failure.class,()->commit(runtime,missing));
        assertThrows(Failure.class,()->service.commitResult(runtime,"task","run","manifest_"+HASH,dataResult()));
    }
    @Test void arbitraryToolPolicyOrProviderRevisionNeverGrantsTools() {
        var snapshot=snapshot();snapshot.put("skills",List.of());snapshot.put("toolPolicyRef","all-tools");
        assertThrows(Failure.class,()->service.create(owner,new SkillExecutionService.Create("action","task","42",1,"agent","grant",1,7,snapshot)));
        assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","provider",new SkillExecutionService.EffectStart("exec",skill.getSnapshotSha256(),fence,HASH,"revision")));
        verify(pwe,never()).insert(any());
    }
    @Test void unfinishedRetiredOwnerViewIsReadOnlyAndCannotAdvertiseRecovery() {
        clearInvocations(storage);
        when(pwe.listInputs("0","client","owner","exec")).thenReturn(List.of());
        for (int protocol : List.of(1,2,3,4)) {
            execution.setExecutionProtocolVersion(protocol).setExecutionState("QUEUED")
                    .setFailureCode(null).setFailureMessage(null).setOutputContentMimeType("image/png");
            var view=service.get(owner,"exec");
            assertEquals("QUEUED",view.state());
            assertEquals("UNSUPPORTED_RETIRED_PROTOCOL",view.recoveryStatus());
            assertNull(view.runtimeCommand());assertNull(view.failureCode());assertNull(view.failureMessage());
            var error=assertThrows(Failure.class,()->service.resume(runtime,"task","run",
                    new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())));
            assertEquals(Reason.NOT_FOUND,error.getReason());
            execution.setExecutionState("OUTPUT_STAGED");
            assertEquals("UNSUPPORTED_RETIRED_PROTOCOL",service.get(owner,"exec").recoveryStatus());
            execution.setExecutionState("OUTPUT_COMMITTED");
            assertNull(service.get(owner,"exec").recoveryStatus());
        }
        execution.setExecutionProtocolVersion(5).setExecutionState("QUEUED");
        assertNull(service.get(owner,"exec").recoveryStatus());
        verify(pwe,never()).update(any());verifyNoInteractions(storage);
    }
    @Test void legacyConversationProtocolsCannotClaimResumeStageOrReportFailure() {
        clearInvocations(storage);
        for(int protocol:List.of(1,2,3)) {
            execution.setExecutionProtocolVersion(protocol);String snapshot=skill.getSnapshotJson();
            assertThrows(Failure.class,()->service.claimConversationStart(runtime,"task","run","command","message"));
            assertThrows(Failure.class,()->service.renewConversationLease(runtime,"task","run",fence));
            assertThrows(Failure.class,()->service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())));
            assertThrows(Failure.class,()->service.stageConversationOutput(runtime,"task","run",fence,"output_1","a.txt","text/plain",new byte[]{1}));
            assertThrows(Failure.class,()->service.failConversation(runtime,"task","run",fence,"NATIVE_EXECUTION_TURN_FAILED"));
            assertEquals("QUEUED",execution.getExecutionState());assertEquals(snapshot,skill.getSnapshotJson());
        }
        assertThrows(Failure.class,()->service.createConversation(owner,null));
        verify(pwe,never()).update(any());verify(attachments,never()).compareAndSet(any(),anyLong(),anyString());verifyNoInteractions(storage);
    }
    @Test void retiredRuntimeControllerHasNoOldCommandStartOrCommitRoutes() {
        var methods=Arrays.stream(cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        for(String retired:List.of("controlledV3Commands","providerStart","controlledProviderStart","controlledProviderStartV3","inputsV3","commit","recoverStaged","recoverUploaded"))assertFalse(methods.contains(retired));
        var point=Arrays.stream(cn.jia.agent.api.ControlledImagePointAndStartController.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        assertFalse(point.contains("submit"));assertTrue(point.contains("get"));
    }
    @Test void runtimeCredentialLaneRejectsRetiredV3AndOldOutputCommitButKeepsV5() {
        for(String path:List.of("/internal/agent/tasks/conversation-executions/controlled-image-v3-commands",
                "/internal/agent/tasks/task/runs/run/conversation/provider-start-controlled-image-v3",
                "/internal/agent/tasks/task/runs/run/conversation/inputs-v3",
                "/internal/agent/tasks/task/runs/run/conversation/output-commits/manifest")) {
            var request=new org.springframework.mock.web.MockHttpServletRequest(path.contains("-commands")?"GET":"POST",path);
            assertFalse(cn.jia.agent.security.AgentRuntimeAuthenticationFilter.allowed(request));
        }
        for(String path:List.of("/internal/agent/tasks/task/runs/run/conversation/resume",
                "/internal/agent/tasks/task/runs/run/conversation/result-commits/manifest"))
            assertTrue(cn.jia.agent.security.AgentRuntimeAuthenticationFilter.allowed(new org.springframework.mock.web.MockHttpServletRequest("POST",path)));
    }
    @Test void legacyV4WritesAreRetiredWithoutStateRewrites() {
        execution.setExecutionProtocolVersion(4);String before=skill.getSnapshotJson();
        assertThrows(Failure.class,()->service.resume(runtime,"task","run",new SkillExecutionService.Resume("exec",skill.getSnapshotSha256())));
        assertThrows(Failure.class,()->service.startEffect(runtime,"task","run","first",start()));
        assertThrows(Failure.class,()->service.inputs(runtime,"task","run",fence));assertThrows(Failure.class,()->commit(runtime,dataResult()));
        assertThrows(Failure.class,()->service.markProjected(owner,"exec",HASH));
        assertEquals("QUEUED",execution.getExecutionState());assertEquals(before,skill.getSnapshotJson());assertEquals("{}",skill.getEffectsJson());
        verify(pwe,never()).update(any());verify(attachments,never()).compareAndSet(any(),anyLong(),anyString());
    }
    @Test void legacyCompletedResultRemainsReadOnlyWithItsOriginalDigest() {
        execution.setExecutionProtocolVersion(4).setExecutionState("RESULT_COMMITTED");skill.setPhase("COMPLETE");
        var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion",1);manifest.put("data",Map.of("answer","history"));manifest.put("artifacts",List.of());manifest.put("inputReceipts",List.of());
        String digest=sha(json(manifest));manifest.put("resultDigest",digest);manifest.put("manifestId","manifest_"+digest);skill.setResultManifestJson(json(manifest));
        var view=service.result(owner,"exec");assertEquals(digest,view.resultManifest().get("resultDigest"));assertEquals(1,view.resultManifest().get("schemaVersion"));
        verify(pwe,never()).update(any());verify(attachments,never()).compareAndSet(any(),anyLong(),anyString());
    }

    /** Existing fixture has one authenticated owner. Cross-owner fixtures supply explicit grant authority separately. */
    private static void wireAssigneeFixture(cn.jia.agent.service.PersonalWorkspaceExecutionService instance,
            cn.jia.agent.dao.PersonalWorkspaceExecutionDao rows) {
        var service=(cn.jia.agent.service.impl.PersonalWorkspaceExecutionServiceImpl)instance;
        var authority=org.mockito.Mockito.mock(cn.jia.agent.service.TaskAssigneeAuthorityService.class);
        org.mockito.Mockito.when(authority.resolveForAssignment(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(i->new cn.jia.agent.service.TaskAssigneeAuthorityService.Target(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),1L,null));
        org.mockito.Mockito.when(authority.requireGrantTarget(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(i->new cn.jia.agent.service.TaskAssigneeAuthorityService.Target(i.getArgument(0),i.getArgument(1),i.getArgument(2),"agent",1L,null));
        service.setAssigneeAuthority(authority);
        org.mockito.Mockito.when(rows.findForRuntime(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(i->rows.findByTaskRun(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(4),i.getArgument(5)));
    }
}
