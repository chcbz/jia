package cn.jia.chat.service;

import cn.jia.agent.service.*;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatActionCapabilityServiceTest {
    final InstalledSkillDescriptorLookup installed = mock(InstalledSkillDescriptorLookup.class);
    final SkillCapabilityLookup runtime = mock(SkillCapabilityLookup.class);
    final TaskSkillPolicyService policies = mock(TaskSkillPolicyService.class);
    final ChatActionCapabilityService service = new ChatActionCapabilityService(installed, runtime, policies);
    final ChatActionCapabilityService.Scope scope = new ChatActionCapabilityService.Scope("0", "owner", "client", "agent");
    final SkillCapabilityLookup.Scope key = new SkillCapabilityLookup.Scope("0", "client", "owner", "agent");
    final AgentManagedSessionLookup.SessionFence fence = new AgentManagedSessionLookup.SessionFence("installation", "host", "runtime", 1);
    InstalledSkillDescriptorLookup.Descriptor descriptor(String reason) {
        return new InstalledSkillDescriptorLookup.Descriptor("image-edit", "1.0.0", "install", "a".repeat(64), "b".repeat(64),
                reason == null ? "{\"title\":\"图片修改\",\"runner\":\"provider-operation\",\"requirements\":{},\"parametersSchema\":{},\"inputs\":{\"acceptedMimeTypes\":[],\"minItems\":0,\"maxItems\":0},\"outputs\":{\"mimeTypes\":[\"text/plain\"]}}" : null, "installation", "host", reason);
    }
    SkillCapabilityLookup.View ready(String revision, String digest, SkillCapabilityLookup.Readiness readiness, String reason) {
        return new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE, "session", fence, new SkillCapabilityLookup.Catalog(revision,
                List.of(new SkillCapabilityLookup.RuntimeSkill("image-edit", "1.0.0", digest, "b".repeat(64), "provider-operation", readiness, reason, "c".repeat(64), "binding-1"))));
    }
    @BeforeEach void setup() {
        when(installed.readCurrent(key)).thenAnswer(invocation -> installed.read(key));
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor(null))));
        when(runtime.lookup(key, fence)).thenReturn(ready("1", "a".repeat(64), SkillCapabilityLookup.Readiness.READY, null));
    }
    @Test void profileAndNewTaskCatalogExcludeHistoricalVersions() {
        var old=new InstalledSkillDescriptorLookup.Descriptor("image-edit","0.9.0","old-install",
                "d".repeat(64),"e".repeat(64),"{}","installation","host",null);
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(old,descriptor(null))));
        doReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor(null)))).when(installed).readCurrent(key);
        assertEquals(1,service.inspectInstalledSkills(scope,fence).items().size());
        assertEquals("RUNTIME_READY",service.inspectInstalledSkills(scope,fence).items().getFirst().state());
        assertEquals(1,service.inspectInstalledSkillsWithoutRuntime(scope).items().size());
        when(policies.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        assertEquals(1,service.taskCatalog(scope,"task").items().size());
        assertEquals(2,installed.read(key).skills().size());
    }
    @Test void sameVersionReinstallRemainsCallableFromCurrentInstallationOnly() {
        var current=descriptor(null);
        var historical=new InstalledSkillDescriptorLookup.Descriptor(current.skillKey(),current.skillVersion(),
                "historical-install",current.packageSha256(),current.manifestSha256(),current.manifestJson(),
                "installation","host",null);
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(historical,current)));
        doReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(current))).when(installed).readCurrent(key);
        when(policies.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        assertEquals("CALLABLE",service.taskCatalog(scope,"task").items().getFirst().get("state"));
        assertEquals(1,service.available(scope,"task",List.of()).size());
        assertEquals(2,installed.read(key).skills().size()); // History remains available to pinned executions.
    }
    @Test void ambiguousCurrentInstallationsNeverBecomeActions() {
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor(null),descriptor(null))));
        when(policies.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        assertTrue(service.available(scope,"task",List.of()).isEmpty());
    }
    @Test void changingCurrentInstallationDuringDiscoveryRetractsActions() {
        var current=new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor(null)));
        var removed=new InstalledSkillDescriptorLookup.Snapshot(List.of());
        // UI catalog read, followed by action discovery and its stability check.
        doReturn(current,current,removed).when(installed).readCurrent(key);
        when(policies.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        assertTrue(service.available(scope,"task",List.of()).isEmpty());
    }
    @Test void verifiedInstallationAndRuntimeAreIndependentWithoutTaskPolicy() {
        var item = service.inspectInstalledSkills(scope, fence).items().getFirst(); assertTrue(item.installationVerified()); assertEquals("RUNTIME_READY", item.state());
        verifyNoInteractions(policies);
        item = service.inspectInstalledSkillsWithoutRuntime(scope).items().getFirst(); assertTrue(item.installationVerified()); assertEquals("SKILL_RUNTIME_UNAVAILABLE", item.reasonCode());
    }
    @Test void noProofNeverConsultsRuntimeAndMissingReceiptNeverReady() {
        var result = service.inspectInstalledSkillsWithoutRuntime(scope); verifyNoInteractions(runtime, policies); assertNull(result.catalogRevision());
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor("SKILL_INSTALLATION_RECEIPT_MISSING"))));
        assertFalse(service.inspectInstalledSkillsWithoutRuntime(scope).items().getFirst().installationVerified());
    }
    @Test void runtimeChangedRetractsReadyButNotInstallation() {
        when(runtime.lookup(key, fence)).thenReturn(ready("1", "a".repeat(64), SkillCapabilityLookup.Readiness.READY, null), SkillCapabilityLookup.View.unavailable(SkillCapabilityLookup.State.DISCONNECTED));
        var item = service.inspectInstalledSkills(scope, fence).items().getFirst(); assertTrue(item.installationVerified()); assertEquals("SKILL_RUNTIME_CHANGED", item.reasonCode());
    }
    @Test void bindingMismatchAndArbitraryReadinessFailureCannotBecomeReady() {
        when(runtime.lookup(key, fence)).thenReturn(ready("1", "d".repeat(64), SkillCapabilityLookup.Readiness.READY, null));
        assertEquals("SKILL_RUNTIME_BINDING_MISMATCH", service.inspectInstalledSkills(scope, fence).items().getFirst().reasonCode());
        when(runtime.lookup(key, fence)).thenReturn(ready("1", "a".repeat(64), SkillCapabilityLookup.Readiness.UNAVAILABLE, "PROVIDER_UNAVAILABLE"));
        var item = service.inspectInstalledSkills(scope, fence).items().getFirst(); assertTrue(item.installationVerified()); assertEquals("PROVIDER_UNAVAILABLE", item.reasonCode());
    }
    @Test void duplicateTaskCatalogWireAndCountStayUnchanged() {
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor(null), descriptor(null))));
        when(policies.read(key, "task")).thenReturn(new TaskSkillPolicyService.Snapshot(true, null, 1, fence, key));
        var task = service.taskCatalog(scope, "task"); assertEquals(2, task.items().size());
        for (var item : task.items()) { assertEquals(java.util.Set.of("skillKey", "skillVersion", "title", "state", "reasonCode", "costInfo"), item.keySet()); assertEquals("UNAVAILABLE", item.get("state")); }
    }
    @Test void taskWithoutFenceRetainsItsWireAndDeniedTaskNeverEnumerates() {
        when(policies.read(key, "task")).thenReturn(new TaskSkillPolicyService.Snapshot(false, "RUNTIME_UNAVAILABLE", 1, null, key));
        assertEquals(1, service.taskCatalog(scope, "task").items().size()); verifyNoInteractions(runtime);
        reset(installed); when(policies.read(key, "task")).thenReturn(new TaskSkillPolicyService.Snapshot(false, "TASK_GRANT_MISSING", 1, null, null));
        assertTrue(service.taskCatalog(scope, "task").items().isEmpty()); verifyNoInteractions(installed);
    }
    @Test void sourceFailureIsNotAnEmptySuccessAndNoFenceCannotBeForged() {
        when(installed.read(key)).thenThrow(new InstalledSkillDescriptorLookup.SourceUnavailable());
        assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class, () -> service.inspectInstalledSkillsWithoutRuntime(scope));
        assertThrows(NullPointerException.class, () -> service.inspectInstalledSkills(scope, null));
    }
    @Test void nativeAdvertisementRequiresSameFencedSessionAndIntersectsActualCapabilities() {
        var executions=mock(PersonalWorkspaceExecutionService.class);
        service.setNativeExecutions(executions);
        when(executions.nativeLocalFileCapabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(
                List.of("text/plain"),List.of("text/plain"),true));
        when(policies.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of()));
        when(runtime.lookup(key,fence)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,
                "session",fence,new SkillCapabilityLookup.Catalog("1",List.of())));
        var declaration=java.util.Map.<String,Object>of("nativeExecution",java.util.Map.of("outputMimeTypes",List.of("text/plain","application/json")));
        var catalog=List.<java.util.Map<String,Object>>of(java.util.Map.of("contentMimeType","text/plain"),java.util.Map.of("contentMimeType","image/png"));
        var actions=service.availableNative(scope,"task",catalog,declaration,"session");
        assertEquals(1,actions.size());var action=actions.getFirst();
        assertEquals("EXECUTE_NATIVE",action.get("operation"));assertNull(action.get("skill"));
        assertEquals(List.of("text/plain"),action.get("outputMimeTypes"));assertEquals(List.of("text/plain"),action.get("inputMimeTypes"));
        when(executions.nativeLocalFileCapabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(List.of("text/plain","image/png"),List.of("image/png"),true));
        var imageDeclaration=java.util.Map.<String,Object>of("nativeExecution",java.util.Map.of("outputMimeTypes",List.of("image/png")));
        var imageAction=service.availableNative(scope,"task",catalog,imageDeclaration,"session").getFirst();
        assertEquals(List.of("image/png"),imageAction.get("outputMimeTypes"));assertEquals(List.of("image/png"),imageAction.get("inputMimeTypes"));
        assertTrue(service.availableNative(scope,"task",catalog,declaration,"other-session").isEmpty());
        assertTrue(service.availableNative(scope,"task",catalog,java.util.Map.of(),"session").isEmpty());
        var docx="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        var xlsx="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        when(executions.nativeLocalFileCapabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(
                List.of(docx,xlsx,"text/plain"),List.of(docx,xlsx),true));
        var officeDeclaration=java.util.Map.<String,Object>of("nativeExecution",java.util.Map.of("outputMimeTypes",List.of(docx,xlsx,"image/png")));
        var officeCatalog=List.<java.util.Map<String,Object>>of(java.util.Map.of("contentMimeType",docx),java.util.Map.of("contentMimeType",xlsx));
        var officeAction=service.availableNative(scope,"task",officeCatalog,officeDeclaration,"session").getFirst();
        assertEquals(List.of(xlsx,docx),officeAction.get("outputMimeTypes"));
        assertEquals(List.of(xlsx,docx),officeAction.get("inputMimeTypes"));
        when(executions.nativeLocalFileCapabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(List.of("application/json"),List.of(),true));
        assertTrue(service.availableNative(scope,"task",catalog,java.util.Map.of("nativeExecution",java.util.Map.of("outputMimeTypes",List.of("text/plain"))),"session").isEmpty());
        when(executions.nativeLocalFileCapabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(List.of(),List.of(),false));
        assertTrue(service.availableNative(scope,"task",catalog,declaration,"session").isEmpty());
    }
    @Test void instructionNativeActionRequiresAdvertisedPolicyAndExactEnabledInstallation() {
        var executions=mock(PersonalWorkspaceExecutionService.class);service.setNativeExecutions(executions);
        when(executions.nativeLocalFileCapabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(List.of("text/plain"),List.of(),true));
        when(policies.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        var d=new InstalledSkillDescriptorLookup.Descriptor("ordinary","1.0","install-1","a".repeat(64),null,null,"installation","host",null);
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(d)));
        var fact=new SkillCapabilityLookup.RuntimeSkill("ordinary","1.0","a".repeat(64),null,null,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE,null,null,null,"install-1",null,"INSTRUCTION",null,true,null);
        when(runtime.lookup(key,fence)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",fence,new SkillCapabilityLookup.Catalog("1",List.of(fact))));
        var old=java.util.Map.<String,Object>of("nativeExecution",java.util.Map.of("outputMimeTypes",List.of("text/plain")));
        assertEquals(1,service.availableNative(scope,"task",List.of(),old,"session").size());
        var decl=java.util.Map.<String,Object>of("nativeExecution",java.util.Map.of("outputMimeTypes",List.of("text/plain"),"supportedToolPolicyRefs",List.of("NATIVE_SKILL_SCRIPT_V1")));
        var actions=service.availableNative(scope,"task",List.of(),decl,"session");assertEquals(2,actions.size());
        var action=actions.get(1);var skill=(java.util.Map<?,?>)action.get("skill");assertEquals("ordinary",skill.get("skillKey"));assertTrue(skill.containsKey("manifestSha256"));assertNull(skill.get("manifestSha256"));
        assertEquals(ChatActionCapabilityService.nativeSkillActionId(d,List.of(),List.of("text/plain")),action.get("actionId"));
        var parsed=ChatActionOutcomeContract.facts(java.util.Map.of("schemaVersion",6,"availableActions",List.of(action),"availableSources",List.of(),"inspectedSourceRefIds",List.of()));
        var frozen=ChatActionExecutionService.nativeSelection(new InstalledSkillDescriptorLookup.Snapshot(List.of(d)),parsed.availableActions().getFirst());
        var frozenSkill=(java.util.Map<?,?>)frozen.get("skill");assertEquals("install-1",frozenSkill.get("installationId"));assertEquals("a".repeat(64),frozenSkill.get("packageSha256"));
        assertTrue(frozenSkill.containsKey("manifestSha256"));assertNull(frozenSkill.get("manifestSha256"));assertNull(frozen.get("providerBinding"));
        var replaced=new InstalledSkillDescriptorLookup.Descriptor("ordinary","1.0","install-2","a".repeat(64),null,null,"installation","host",null);
        assertNotEquals(action.get("actionId"),ChatActionCapabilityService.nativeSkillActionId(replaced,List.of(),List.of("text/plain")));
        assertThrows(ChatActionDispatchService.Rejected.class,()->ChatActionExecutionService.nativeSelection(new InstalledSkillDescriptorLookup.Snapshot(List.of(replaced)),parsed.availableActions().getFirst()));
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(replaced)));
        assertEquals(1,service.availableNative(scope,"task",List.of(),decl,"session").size());
        var disabled=new SkillCapabilityLookup.RuntimeSkill("ordinary","1.0","a".repeat(64),null,null,SkillCapabilityLookup.Readiness.UNKNOWN,null,null,null,"install-1",null,"INSTRUCTION",null,false,null);
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(d)));
        when(runtime.lookup(key,fence)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",fence,new SkillCapabilityLookup.Catalog("1",List.of(disabled))));
        assertEquals(1,service.availableNative(scope,"task",List.of(),decl,"session").size());
    }
}
