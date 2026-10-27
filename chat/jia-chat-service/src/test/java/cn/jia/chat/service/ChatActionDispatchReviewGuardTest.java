package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatActionDispatchReviewGuardTest {
    final ChatActionFinalService finals=mock(ChatActionFinalService.class);
    final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    final ChatTypedDeliberationStore typed=mock(ChatTypedDeliberationStore.class);
    final ChatDeliberationOutboxService outbox=mock(ChatDeliberationOutboxService.class);
    final ChatActionDispatchService.Executor executor=mock(ChatActionDispatchService.Executor.class);
    final ChatTypedReviewContextService review=mock(ChatTypedReviewContextService.class);
    final ChatDeliberationOutboxService.Claim claim=new ChatDeliberationOutboxService.Claim(new ChatDispatchOutboxEntity(),false);
    final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","123",1);
    final ChatActionFinalService.BoundAction action=action();
    final ChatActionDispatchService service;
    @SuppressWarnings("unchecked") ChatActionDispatchReviewGuardTest() {
        ObjectProvider<ChatActionDispatchService.Executor> provider=mock(ObjectProvider.class);when(provider.getIfUnique()).thenReturn(executor);
        service=new ChatActionDispatchService(finals,tasks,bindings,conversations,scopes,typed,mock(ChatTypedInspectionContextService.class),
                mock(ChatDeliberationService.class),mock(ChatDeliberationDao.class),outbox,provider);
        service.setReviewContexts(review);when(finals.loadAction(any())).thenReturn(action);
    }
    ChatActionFinalService.BoundAction action() {
        var row=new ChatTypedDeliberationStore.Outcome("outcome",scope,"request",1,"turn","task",1,100,"sha256:"+"a".repeat(64),
                "ACTION_REQUEST","处理","{}","{}","{}","[]",1);
        var capability=new ChatActionOutcomeContract.Capability("execute","EXECUTE","EXECUTE_SKILL",null,Map.of(),List.of(),0,0,List.of());
        var union=new ChatActionOutcomeContract.Outcome(5,"ACTION_REQUEST","处理",null,
                new ChatActionOutcomeContract.Action("execute","处理",List.of(),Map.of()));
        var valid=new ChatActionFinalValidator.ValidatedFinal(Map.of("targetAgentId","agent","dispatchId","dispatch"),
                new ChatActionOutcomeContract.Facts(5,List.of(capability),List.of(),List.of()),"处理",union,null,"{}",row.finalDigest());
        return new ChatActionFinalService.BoundAction(row,null,valid);
    }
    void root(String state) {
        var root=new AgentTaskMetaEntity().setRewardStatus(state).setTaskVersion(7L).setAssignedAgentId("agent");
        doAnswer(call->((AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(root))
                .when(tasks).executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any());
    }
    void current() {
        root("running");var binding=mock(ChatBountyBindingStore.Binding.class);
        when(binding.conversationId()).thenReturn(123L);when(binding.assignmentRevision()).thenReturn(1L);
        when(bindings.lock(any(),eq("task"))).thenReturn(binding);
        var c=new ChatConversationEntity().setId(123L).setJiacn("owner").setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task").setLifecycleGeneration(1L).setTargetAgentIds("[\"agent\"]");
        c.setTenantId("0");c.setClientId("client");when(conversations.lockScopedById("owner","client","123")).thenReturn(c);
        when(scopes.parsePersistedTargetAgentIds(c.getTargetAgentIds())).thenReturn(List.of("agent"));
        when(outbox.requireActiveClaim(eq(claim),anyLong())).thenReturn(claim.row());when(outbox.sent(eq(claim),anyLong())).thenReturn(true);
    }
    @Test void admissionFrozenReviewRejectsBeforePackagePreparationOrExecutionOrAck() {
        doThrow(new ChatActionDispatchService.Rejected()).when(review).requireActionAllowed(action);
        assertThrows(ChatActionDispatchService.Rejected.class,()->service.consume(claim));
        verifyNoInteractions(executor,tasks,outbox,bindings,conversations);
    }
    @Test void missingFrozenContextIsRetryableFailureNotPermanentRejectionOrNoBatch() {
        doThrow(new IllegalStateException("db unavailable")).when(review).requireActionAllowed(action);
        assertThrows(IllegalStateException.class,()->service.consume(claim));verifyNoInteractions(executor,tasks,outbox);
    }
    @Test void currentReviewTerminalAndUnknownStatesCannotAdmitAnyNewSkill() {
        for(String state:List.of("reviewing","completed","failed","cancelled","archived","unknown")) {
            root(state);assertThrows(ChatActionDispatchService.Rejected.class,()->service.consume(claim),state);
        }
        verify(executor,never()).admit(any());verifyNoInteractions(outbox,conversations);
    }
    @Test void scopeGuardRunsAgainUnderRootBeforeChildAndFencedAck() {
        current();doNothing().doThrow(new ChatActionDispatchService.Rejected()).when(review).requireActionAllowed(action);
        assertThrows(ChatActionDispatchService.Rejected.class,()->service.consume(claim));
        verify(review,times(2)).requireActionAllowed(action);verify(executor,never()).admit(any());verify(outbox,never()).sent(any(),anyLong());
    }
    @Test void noFrozenBatchKeepsExistingExecutionPathAndSingleFencedAck() {
        current();service.consume(claim);
        var order=inOrder(review,executor,outbox);order.verify(review).requireActionAllowed(action);order.verify(executor).prepare(action);
        order.verify(outbox).requireActiveClaim(eq(claim),anyLong());order.verify(review).requireActionAllowed(action);
        order.verify(executor).admit(action);order.verify(outbox).sent(eq(claim),anyLong());
    }
    @Test void losingOutboxAckDoesNotPretendCommitSucceeded() {
        current();when(outbox.sent(eq(claim),anyLong())).thenReturn(false);
        assertThrows(ChatDeliberationException.class,()->service.consume(claim));verify(executor).admit(action);
        // This unit fixture proves exception propagation; only the real transaction test can prove rollback.
    }
    @Test void transientRuntimeLossRetriesPreparationButRevokedGrantStillRejects() {
        var policies=mock(cn.jia.agent.service.TaskSkillPolicyService.class);
        var installed=mock(cn.jia.agent.service.InstalledSkillDescriptorLookup.class);
        var actual=new ChatActionExecutionService(mock(ChatDeliberationService.class),mock(ChatDeliberationDao.class),
                typed,mock(ChatInteractionStepStore.class),mock(cn.jia.agent.service.AgentTaskExecutionGrantService.class),
                mock(cn.jia.agent.service.SkillExecutionService.class),installed,
                mock(cn.jia.agent.service.SkillCapabilityLookup.class),policies);
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope("0","client","owner","agent");
        var fence=new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence("installation","host","runtime",2);
        when(policies.read(key,"task")).thenReturn(
                new cn.jia.agent.service.TaskSkillPolicyService.Snapshot(false,"RUNTIME_UNAVAILABLE",1,null,null),
                new cn.jia.agent.service.TaskSkillPolicyService.Snapshot(true,null,1,fence,key),
                new cn.jia.agent.service.TaskSkillPolicyService.Snapshot(false,"TASK_GRANT_MISSING",1,null,null));
        var unavailable=assertThrows(ChatActionDispatchService.Unavailable.class,()->actual.prepare(action));
        assertEquals("SKILL_RUNTIME_UNAVAILABLE",unavailable.getMessage());verifyNoInteractions(installed);
        assertDoesNotThrow(()->actual.prepare(action));verify(installed).read(key);
        assertThrows(ChatActionDispatchService.Rejected.class,()->actual.prepare(action));
        verifyNoMoreInteractions(installed);
        // An unknown producer reason is not permission and must not permanently kill the action.
        when(policies.read(key,"task")).thenReturn(
                new cn.jia.agent.service.TaskSkillPolicyService.Snapshot(false,"FUTURE_REASON",1,null,null));
        assertEquals("SKILL_POLICY_INDETERMINATE",
                assertThrows(ChatActionDispatchService.Unavailable.class,()->actual.prepare(action)).reasonCode());
        verifyNoMoreInteractions(installed);
    }

    @Test void newExecutionSnapshotUsesCurrentSameVersionInstallationAndRejectsAmbiguity() {
        var installed=mock(cn.jia.agent.service.InstalledSkillDescriptorLookup.class);
        var runtime=mock(cn.jia.agent.service.SkillCapabilityLookup.class);
        var policies=mock(cn.jia.agent.service.TaskSkillPolicyService.class);
        var actual=new ChatActionExecutionService(mock(ChatDeliberationService.class),mock(ChatDeliberationDao.class),
                typed,mock(ChatInteractionStepStore.class),mock(cn.jia.agent.service.AgentTaskExecutionGrantService.class),
                mock(cn.jia.agent.service.SkillExecutionService.class),installed,runtime,policies);
        var key=new cn.jia.agent.service.SkillCapabilityLookup.Scope("0","client","owner","agent");
        var fence=new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence("installation","host","runtime",1);
        when(policies.read(key,"task")).thenReturn(new cn.jia.agent.service.TaskSkillPolicyService.Snapshot(true,null,1,fence,key));
        var descriptor=new cn.jia.agent.service.InstalledSkillDescriptorLookup.Descriptor("text-file","1.0.1","current-install",
                "a".repeat(64),"b".repeat(64),"{\"runner\":\"script\",\"entrypoint\":\"main.py\",\"requirements\":{},\"outputs\":{}}",
                "installation","host",null);
        when(installed.readCurrent(key)).thenReturn(new cn.jia.agent.service.InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor)));
        when(installed.read(key)).thenThrow(new AssertionError("New execution must not choose from history"));
        var report=new cn.jia.agent.service.SkillCapabilityLookup.RuntimeSkill("text-file","1.0.1","a".repeat(64),"b".repeat(64),
                "script",cn.jia.agent.service.SkillCapabilityLookup.Readiness.READY,null,"c".repeat(64),null);
        when(runtime.lookup(key,fence)).thenReturn(new cn.jia.agent.service.SkillCapabilityLookup.View(
                cn.jia.agent.service.SkillCapabilityLookup.State.AVAILABLE,"session",fence,
                new cn.jia.agent.service.SkillCapabilityLookup.Catalog("1",List.of(report))));
        var capability=new ChatActionOutcomeContract.Capability("execute","EXECUTE","EXECUTE_SKILL",
                new ChatActionOutcomeContract.Skill("text-file","1.0.1","a".repeat(64),"b".repeat(64)),Map.of(),List.of(),0,0,List.of());
        var grant=mock(cn.jia.agent.service.AgentTaskExecutionGrantService.Admission.class);
        when(grant.grantId()).thenReturn("grant");when(grant.grantVersion()).thenReturn(1L);
        Map<String,Object> snapshot=org.springframework.test.util.ReflectionTestUtils.invokeMethod(actual,"prepare",action,"act_test",capability,List.of(),grant);
        assertEquals("current-install",((Map<?,?>)snapshot.get("skill")).get("installationId"));
        when(installed.readCurrent(key)).thenReturn(new cn.jia.agent.service.InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor,descriptor)));
        assertThrows(ChatActionDispatchService.Rejected.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                actual,"prepare",action,"act_test",capability,List.of(),grant));
        when(installed.readCurrent(key)).thenReturn(new cn.jia.agent.service.InstalledSkillDescriptorLookup.Snapshot(List.of()));
        assertThrows(ChatActionDispatchService.Rejected.class,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                actual,"prepare",action,"act_test",capability,List.of(),grant));
        verify(installed,never()).read(key);
    }

}
