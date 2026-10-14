package cn.jia.chat.service;

import cn.jia.agent.service.*;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** No mock task grant: this slice deliberately reports runtime readiness, never callable actions. */
class ChatInstalledSkillCapabilityTest {
    private final InstalledSkillDescriptorLookup installed=mock(InstalledSkillDescriptorLookup.class);
    private final SkillCapabilityLookup runtime=mock(SkillCapabilityLookup.class);
    private final ChatActionCapabilityService.Scope scope=new ChatActionCapabilityService.Scope("0","owner","client","agent");
    private final AgentManagedSessionLookup.SessionFence fence=new AgentManagedSessionLookup.SessionFence("installation","host","runtime",7);
    private final SkillCapabilityLookup.Scope key=new SkillCapabilityLookup.Scope("0","client","owner","agent");
    private final TaskSkillPolicyService policy=mock(TaskSkillPolicyService.class);
    private final ChatActionCapabilityService service=new ChatActionCapabilityService(installed,runtime,policy);
    @BeforeEach void setup() { service.setInstalledSkillCapabilities(installed,runtime); }
    private static InstalledSkillDescriptorLookup.Descriptor descriptor(String name) {
        return new InstalledSkillDescriptorLookup.Descriptor(name,"1.0.0","install_"+name,"a".repeat(64),"b".repeat(64),
                "{\"title\":\""+name+"\",\"runner\":\"native-model\",\"entrypoint\":\"private/path\","
                +"\"parametersSchema\":{},\"inputs\":{\"minItems\":0,\"maxItems\":32},\"outputs\":{},\"costInfo\":{\"mode\":\"UNKNOWN\"}}",
                "installation","host",null);
    }
    private static SkillCapabilityLookup.RuntimeSkill report(String name) {
        return new SkillCapabilityLookup.RuntimeSkill(name,"1.0.0","a".repeat(64),"b".repeat(64),
                "native-model",SkillCapabilityLookup.Readiness.READY,null,"c".repeat(64),null);
    }
    private SkillCapabilityLookup.View view(List<SkillCapabilityLookup.RuntimeSkill> reports) {
        return new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",fence,
                new SkillCapabilityLookup.Catalog("9",reports));
    }
    private void ready() {
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor("text-file"),descriptor("pdf-export"))));
        when(runtime.lookup(key,fence)).thenReturn(view(List.of(report("text-file"),report("pdf-export"))));
    }
    @Test void multipleSkillsIncludingTextWithInputsAreObservedWithoutSkillNameBranchesOrFeeAuthorization() {
        ready();var result=service.inspectInstalledSkills(scope,fence);
        assertEquals("9",result.catalogRevision());assertEquals(2,result.items().size());
        assertTrue(result.items().stream().allMatch(c->"RUNTIME_READY".equals(c.state())&&c.reasonCode()==null));
        assertTrue(result.items().getFirst().metadataJson().contains("maxItems\":32"));
        assertTrue(result.items().getFirst().metadataJson().contains("UNKNOWN"));
        assertFalse(result.toString().contains("private/path"));assertFalse(result.toString().contains("install_text-file"));
        verifyNoInteractions(policy); // not EXECUTE_SKILL until task admission exists
        assertThrows(UnsupportedOperationException.class,()->result.items().clear());
    }
    @Test void agentReportedButUninstalledSkillIsNotAdvertised() {
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of()));
        when(runtime.lookup(key,fence)).thenReturn(view(List.of(report("text-file"))));
        assertEquals(List.of(),service.inspectInstalledSkills(scope,fence).items());
    }
    @Test void missingDependencyReasonAndNoManifestNeverBecomeReady() {
        ready();var missing=new SkillCapabilityLookup.RuntimeSkill("text-file","1.0.0","a".repeat(64),"b".repeat(64),
                "native-model",SkillCapabilityLookup.Readiness.UNAVAILABLE,"SKILL_TOOL_MISSING",null,null);
        when(runtime.lookup(key,fence)).thenReturn(view(List.of(missing)));
        var result=service.inspectInstalledSkills(scope,fence);
        assertEquals("SKILL_TOOL_MISSING",result.items().getFirst().reasonCode());
        assertEquals("SKILL_RUNTIME_NOT_DECLARED",result.items().getLast().reasonCode());
    }
    @Test void digestOrRunnerMismatchIsUnavailable() {
        ready();var bad=new SkillCapabilityLookup.RuntimeSkill("text-file","1.0.0","d".repeat(64),"b".repeat(64),
                "native-model",SkillCapabilityLookup.Readiness.READY,null,"c".repeat(64),null);
        when(runtime.lookup(key,fence)).thenReturn(view(List.of(bad)));
        assertEquals("SKILL_RUNTIME_BINDING_MISMATCH",service.inspectInstalledSkills(scope,fence).items().getFirst().reasonCode());
    }
    @Test void runtimeInstallationHostMustMatchInstallationReceiptBinding() {
        ready();var foreign=new AgentManagedSessionLookup.SessionFence("other","host","runtime",7);
        when(runtime.lookup(key,foreign)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",foreign,
                new SkillCapabilityLookup.Catalog("9",List.of(report("text-file")))));
        assertEquals("SKILL_RUNTIME_BINDING_MISMATCH",service.inspectInstalledSkills(scope,foreign).items().getFirst().reasonCode());
    }
    @Test void disconnectAmbiguityAndInvalidDeclarationAreNotEmptySuccessOrOldReady() {
        ready();for(var state:List.of(SkillCapabilityLookup.State.DISCONNECTED,SkillCapabilityLookup.State.AMBIGUOUS,
                SkillCapabilityLookup.State.INVALID_DECLARATION,SkillCapabilityLookup.State.FENCED)) {
            when(runtime.lookup(key,fence)).thenReturn(SkillCapabilityLookup.View.unavailable(state));
            var result=service.inspectInstalledSkills(scope,fence);assertNull(result.catalogRevision());
            assertTrue(result.items().stream().allMatch(c->("SKILL_RUNTIME_"+state.name()).equals(c.reasonCode())));
        }
    }
    @Test void changedSessionDuringJoinInvalidatesAllPreviouslyReady() {
        ready();when(runtime.lookup(key,fence)).thenReturn(view(List.of(report("text-file"),report("pdf-export"))),
                SkillCapabilityLookup.View.unavailable(SkillCapabilityLookup.State.DISCONNECTED));
        var result=service.inspectInstalledSkills(scope,fence);assertNull(result.catalogRevision());
        assertTrue(result.items().stream().allMatch(c->"SKILL_RUNTIME_CHANGED".equals(c.reasonCode())));
    }
    @Test void duplicateInstallationsDoNotSelectAnyWinner() {
        ready();when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor("text-file"),descriptor("text-file"))));
        assertTrue(service.inspectInstalledSkills(scope,fence).items().stream()
                .allMatch(c->"SKILL_INSTALLATION_AMBIGUOUS".equals(c.reasonCode())));
    }
    @Test void missingToolchainEvidenceCannotBecomeReadyEvenIfSelfReportedReady() {
        ready();var bad=new SkillCapabilityLookup.RuntimeSkill("text-file","1.0.0","a".repeat(64),"b".repeat(64),
                "native-model",SkillCapabilityLookup.Readiness.READY,null,null,null);
        when(runtime.lookup(key,fence)).thenReturn(view(List.of(bad)));
        assertEquals("SKILL_RUNTIME_EVIDENCE_INVALID",service.inspectInstalledSkills(scope,fence).items().getFirst().reasonCode());
    }
    @Test void nativeSkillWithProviderRequirementStillRequiresBindingEvidence() {
        ready();var original=descriptor("text-file");
        var needingProvider=new InstalledSkillDescriptorLookup.Descriptor(original.skillKey(),original.skillVersion(),
                original.installationId(),original.packageSha256(),original.manifestSha256(),
                original.manifestJson().replace("\"runner\":\"native-model\"","\"runner\":\"native-model\",\"requirements\":{\"providerAlias\":\"model-default\"}"),
                original.runtimeInstallationId(),original.runtimeHostId(),null);
        when(installed.read(key)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(needingProvider)));
        assertEquals("SKILL_RUNTIME_EVIDENCE_INVALID",service.inspectInstalledSkills(scope,fence).items().getFirst().reasonCode());
    }
    @Test void trueDescriptorSourceFailurePropagatesInsteadOfReportingEmptyCatalog() {
        when(installed.read(key)).thenThrow(new InstalledSkillDescriptorLookup.SourceUnavailable());
        assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->service.inspectInstalledSkills(scope,fence));
        verifyNoInteractions(runtime);
    }

    @Test void taskPolicyNotRuntimeReadyDecidesCallabilityAndUnknownCostIsNotZero() {
        ready();when(policy.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(false,"TASK_SKILL_POLICY_DENIED",4,fence,key));
        var blocked=service.taskCatalog(scope,"task");assertTrue(blocked.items().stream().allMatch(x->"UNAVAILABLE".equals(x.get("state"))));
        assertEquals(java.util.Map.of("mode","UNKNOWN"),blocked.items().getFirst().get("costInfo"));
        when(policy.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,4,fence,key));
        assertTrue(service.taskCatalog(scope,"task").items().stream().allMatch(x->"CALLABLE".equals(x.get("state"))));
        assertFalse(service.taskCatalog(scope,"task").toString().contains("private/path"));
    }
    @Test void taskPolicyReplacementNeverKeepsOldCallable() {
        ready();when(policy.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,4,fence,key),
                new TaskSkillPolicyService.Snapshot(false,"TASK_SKILL_POLICY_DENIED",5,fence,key));
        assertTrue(service.taskCatalog(scope,"task").items().stream().allMatch(x->"SKILL_TASK_POLICY_CHANGED".equals(x.get("reasonCode"))));
    }
    @Test void delegatedTaskCatalogReadsActualAgentInstallationNotTaskOwnerInstallation() {
        var delegated=new SkillCapabilityLookup.Scope("0","client","agent-owner","agent");
        when(policy.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,4,fence,delegated));
        when(installed.read(delegated)).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor("text-file"))));
        when(runtime.lookup(delegated,fence)).thenReturn(view(List.of(report("text-file"))));
        var result=service.taskCatalog(scope,"task");assertEquals(1,result.items().size());
        assertEquals("CALLABLE",result.items().getFirst().get("state"));
        assertFalse(result.toString().contains("agent-owner"));
        verify(installed,never()).read(key);verify(runtime,never()).lookup(eq(key),any());
    }
    @Test void noDelegationDoesNotExposeOtherAgentsInstalledSkillList() {
        when(policy.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(false,"TASK_GRANT_MISSING",0,null,null));
        assertTrue(service.taskCatalog(scope,"task").items().isEmpty());
        assertTrue(service.available(scope,"task",List.of()).isEmpty());
        verifyNoInteractions(installed,runtime);
    }
    @Test void changedAgentSubjectCannotKeepPreviouslyCallableEvenWhenFenceIsIdentical() {
        ready();var changed=new SkillCapabilityLookup.Scope("0","client","agent-owner","agent");
        when(policy.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,4,fence,key),
                new TaskSkillPolicyService.Snapshot(true,null,4,fence,changed));
        var result=service.taskCatalog(scope,"task");assertFalse(result.items().isEmpty());
        assertTrue(result.items().stream().allMatch(x->"SKILL_TASK_POLICY_CHANGED".equals(x.get("reasonCode"))));
    }
    @Test void taskPolicyCannotSelectDifferentAgentOrClientForInstallationJoin() {
        var foreign=new SkillCapabilityLookup.Scope("0","other-client","agent-owner","agent");
        when(policy.read(key,"task")).thenReturn(new TaskSkillPolicyService.Snapshot(true,null,4,fence,foreign));
        assertEquals(TaskSkillPolicyService.Failure.Reason.INTEGRITY_ERROR,
                assertThrows(TaskSkillPolicyService.Failure.class,()->service.taskCatalog(scope,"task")).reason());
        verifyNoInteractions(installed,runtime);
    }

}
