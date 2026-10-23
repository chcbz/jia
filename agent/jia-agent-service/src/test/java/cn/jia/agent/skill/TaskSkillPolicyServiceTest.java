package cn.jia.agent.skill;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.TaskSkillPolicyServiceImpl;
import cn.jia.agent.security.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Read-only permission provenance, not readiness or pricing-derived authority. */
class TaskSkillPolicyServiceTest {
    final AgentTaskMetaDao tasks=mock(AgentTaskMetaDao.class);
    final AgentTaskExecutionGrantDao grants=mock(AgentTaskExecutionGrantDao.class);
    final AgentIdentityService identity=mock(AgentIdentityService.class);
    final AgentTaskRequirementSnapshotService requirements=mock(AgentTaskRequirementSnapshotService.class);
    final AgentRuntimeAuthenticationService runtime=mock(AgentRuntimeAuthenticationService.class);
    final SkillCapabilityLookup.Scope scope=new SkillCapabilityLookup.Scope("0","client","owner","agent");
    final TaskAssigneeAuthorityService authority=mock(TaskAssigneeAuthorityService.class);
    AgentTaskMetaEntity task;AgentTaskExecutionGrantEntity grant;TaskSkillPolicyServiceImpl service;
    @BeforeEach void setup() {
        service=new TaskSkillPolicyServiceImpl(tasks,grants,identity,requirements,runtime);
        service.setAssigneeAuthority(authority);
        when(authority.requireGrantTarget("0","client","owner","task","grant",1))
                .thenReturn(new TaskAssigneeAuthorityService.Target("0","client","owner","agent",1,null));
        task=new AgentTaskMetaEntity().setTaskId("task").setOwnerJiacn("owner").setAssignedAgentId("agent").setTaskVersion(8L).setRewardStatus("assigned");task.setTenantId("0");task.setClientId("client");
        grant=new AgentTaskExecutionGrantEntity().setGrantId("grant").setTaskId("task").setOwnerJiacn("owner").setTargetAgentId("agent").setState("ACTIVE").setAssignmentRevision(7L).setGrantVersion(1L).setRequirementRevision(1L).setPermittedOperationsJson("[\"DELIBERATE\",\"EXECUTE_SKILL\"]").setPermittedToolPolicyRef("SKILLS_V1");grant.setTenantId("0");grant.setClientId("client");
        when(identity.requireCanonicalAgentIdInScope("0","client","owner","agent")).thenReturn("agent");when(tasks.findByTaskIdInOwnerScope("0","client","owner","task")).thenReturn(task);when(grants.findActiveByTask("0","client","owner","task")).thenReturn(grant);when(grants.latestAssignmentEventJson("0","client","owner","task")).thenReturn("{\"resultVersion\":7}");
        when(requirements.requireCurrent(any(),eq("task"),eq(1L))).thenReturn(new AgentTaskRequirementSnapshotService.Snapshot("0","client","owner","task",1,"title","description","a".repeat(64),"CREATE"));
        when(runtime.currentRegisteredProof("0","client","agent")).thenReturn(new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0","client","owner","agent","runtime"),"installation","host",3,"a".repeat(64),4,3));
    }
    @Test void actualGrantAndLiveNativeProofAreRequired() {var result=service.read(scope,"task");assertTrue(result.allowed());assertEquals(7,result.assignmentRevision());assertEquals("runtime",result.fence().runtimeInstanceId());verify(tasks).findByTaskIdInOwnerScope("0","client","owner","task");verifyNoMoreInteractions(tasks);}
    @Test void noToolsCannotInheritSkillsFromReadyRuntime() {grant.setPermittedToolPolicyRef("NO_TOOLS_V1");var result=service.read(scope,"task");assertFalse(result.allowed());assertEquals("SKILL_BASELINE_REQUIRED",result.reasonCode());verifyNoInteractions(runtime);}
    @Test void unknownCostHasNoAuthorizationGate() {grant.setCostAuthorizationRef(null);assertTrue(service.read(scope,"task").allowed());}
    @Test void wrongOwnerReturnedByDaoIsIntegrityError() {task.setOwnerJiacn("other");assertEquals(TaskSkillPolicyService.Failure.Reason.INTEGRITY_ERROR,assertThrows(TaskSkillPolicyService.Failure.class,()->service.read(scope,"task")).reason());}
    @Test void missingObjectIs404ButReadFailureIs503() {when(tasks.findByTaskIdInOwnerScope("0","client","owner","task")).thenReturn(null);assertEquals(TaskSkillPolicyService.Failure.Reason.NOT_FOUND,assertThrows(TaskSkillPolicyService.Failure.class,()->service.read(scope,"task")).reason());when(tasks.findByTaskIdInOwnerScope("0","client","owner","task")).thenThrow(new IllegalStateException("read unavailable"));assertEquals(TaskSkillPolicyService.Failure.Reason.SOURCE_UNAVAILABLE,assertThrows(TaskSkillPolicyService.Failure.class,()->service.read(scope,"task")).reason());}
    @Test void reassignmentRevocationAndMissingSkillOperationReject() {when(grants.latestAssignmentEventJson("0","client","owner","task")).thenReturn("{\"resultVersion\":9}");assertEquals("ASSIGNMENT_EPOCH_UNPROVEN",service.read(scope,"task").reasonCode());when(grants.latestAssignmentEventJson("0","client","owner","task")).thenReturn("{\"resultVersion\":7}");grant.setState("REVOKED");assertEquals("TASK_GRANT_STALE",service.read(scope,"task").reasonCode());grant.setState("ACTIVE").setPermittedOperationsJson("[\"DELIBERATE\"]");assertEquals("SKILL_BASELINE_REQUIRED",service.read(scope,"task").reasonCode());}
    @Test void offlineIsDeniedAndNotSourceSuccess() {when(runtime.currentRegisteredProof("0","client","agent")).thenThrow(new IllegalArgumentException("offline"));assertEquals("RUNTIME_UNAVAILABLE",service.read(scope,"task").reasonCode());}
    @Test void delegatedTaskUsesAuthenticatedAgentOwnerNotTaskOwner() {
        when(authority.requireGrantTarget("0","client","owner","task","grant",1))
                .thenReturn(new TaskAssigneeAuthorityService.Target("0","client","agent-owner","agent",2,1L));
        when(runtime.currentRegisteredProof("0","client","agent")).thenReturn(new AgentRuntimeAuthenticationService.Proof(
                new AgentRuntimeAuthentication.Scope("0","client","agent-owner","agent","runtime"),"installation","host",3,"a".repeat(64),4,3));
        when(identity.requireCanonicalAgentIdInScope("0","client","agent-owner","agent")).thenReturn("agent");
        var result=service.read(scope,"task");assertTrue(result.allowed());
        assertEquals(new SkillCapabilityLookup.Scope("0","client","agent-owner","agent"),result.runtimeScope());
        verify(identity,never()).requireCanonicalAgentIdInScope("0","client","owner","agent");
        verify(identity).requireCanonicalAgentIdInScope("0","client","agent-owner","agent");
    }
    @Test void unassignedTaskAndMissingGrantNeverProbeExternalRuntimeOrIdentity() {
        task.setAssignedAgentId("different-agent");assertEquals("TASK_NOT_ASSIGNED",service.read(scope,"task").reasonCode());
        task.setAssignedAgentId("agent");when(grants.findActiveByTask("0","client","owner","task")).thenReturn(null);
        assertEquals("TASK_GRANT_MISSING",service.read(scope,"task").reasonCode());
        verifyNoInteractions(runtime,identity);
    }
    @Test void nativeProofMustStillMatchTenantClientAndSelectedAgent() {
        for(var foreign:java.util.List.of(new AgentRuntimeAuthentication.Scope("other","client","agent-owner","agent","runtime"),
                new AgentRuntimeAuthentication.Scope("0","other","agent-owner","agent","runtime"),
                new AgentRuntimeAuthentication.Scope("0","client","agent-owner","other-agent","runtime"))) {
            when(runtime.currentRegisteredProof("0","client","agent")).thenReturn(new AgentRuntimeAuthenticationService.Proof(foreign,"installation","host",3,"a".repeat(64),4,3));
            var result=service.read(scope,"task");assertFalse(result.allowed());assertEquals("RUNTIME_FENCED",result.reasonCode());assertNull(result.runtimeScope());
        }
        verifyNoInteractions(identity);
    }
    @Test void allowedSnapshotCannotOmitNativeSubjectOrFence() {
        assertThrows(IllegalArgumentException.class,()->new TaskSkillPolicyService.Snapshot(true,null,7,null,null));
        assertThrows(NullPointerException.class,()->new TaskSkillPolicyService.Snapshot(true,null,7,null,scope));
    }

}
