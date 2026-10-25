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
                reason == null ? "{\"title\":\"图片修改\",\"runner\":\"provider-operation\",\"requirements\":{}}" : null, "installation", "host", reason);
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
        when(installed.readCurrent(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor(null))));
        assertEquals(1,service.inspectInstalledSkills(scope,fence).items().size());
        assertEquals("RUNTIME_READY",service.inspectInstalledSkills(scope,fence).items().getFirst().state());
        assertEquals(1,service.inspectInstalledSkillsWithoutRuntime(scope).items().size());
        when(policies.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        assertEquals(1,service.taskCatalog(scope,"task").items().size());
        assertEquals(2,installed.read(key).skills().size());
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
}
