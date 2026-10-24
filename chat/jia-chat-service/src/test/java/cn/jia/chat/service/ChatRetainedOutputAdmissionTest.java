package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.TaskAssigneeAuthorityService;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real typed admission ordering/replay; SQL transaction behavior is tested separately. */
class ChatRetainedOutputAdmissionTest {
    final AgentTaskMutationTransaction mutations = mock(AgentTaskMutationTransaction.class);
    final ChatBountyBindingStore bindings = mock(ChatBountyBindingStore.class);
    final ChatConversationDao conversations = mock(ChatConversationDao.class);
    final JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
    final ChatDeliberationService deliberation = mock(ChatDeliberationService.class);
    final ChatDeliberationDao events = mock(ChatDeliberationDao.class);
    final ChatTypedDeliberationContextService contexts = mock(ChatTypedDeliberationContextService.class);
    final ChatTypedDeliberationService typed = mock(ChatTypedDeliberationService.class);
    final ChatTypedDeliberationStore store = mock(ChatTypedDeliberationStore.class);
    final ChatActionFinalService finals = mock(ChatActionFinalService.class);
    final ChatDispatchAuthorityService authority = mock(ChatDispatchAuthorityService.class);
    final ChatSkillPackagePreparationService packages = mock(ChatSkillPackagePreparationService.class);
    final AgentTaskExecutionGrantService grants = mock(AgentTaskExecutionGrantService.class);
    final ChatTypedDiscussionAdmissionService service = new ChatTypedDiscussionAdmissionService(
            mutations, bindings, conversations, scopes, deliberation, events, contexts, typed, finals);
    final ChatTypedDeliberationStore.Scope scope = new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 3);
    final ServerResolvedSender sender = new ServerResolvedSender(ServerResolvedSender.USER_TYPE, "owner", "owner", "client", DisplayNameSource.JIACN);
    final ChatTypedDeliberationWire.DiscussionCommand command = new ChatTypedDeliberationWire.DiscussionCommand(
            "DISCUSSION", "task", 7L, "把刚交付的红图改为绿色，保留白色标记", "outcome", 0L, null, null, List.of(), "TASK_DEFAULT");
    final Map<String,Object> output = Map.of("requestId", "execute", "stepId", "step", "outputId", "red", "sha256", "a".repeat(64));
    final List<Map<String,Object>> targets = List.of(Map.of("outcomeId", "outcome", "finalDigest", "b".repeat(64), "outputSource", output));
    final ChatTypedDeliberationWire.SourceSelector selector = new ChatTypedDeliberationWire.SourceSelector("CURRENT_CONVERSATION_ASSET", null, null, null, "asset", "1");
    final String catalog = CanonicalContextJson.write(List.of(Map.of("sourceKind", "CURRENT_CONVERSATION_ASSET", "sourceRefId", "source-red",
            "contentMimeType", "application/octet-stream", "contentHash", "a".repeat(64), "sourceIdentity", Map.of(
            "producerRequestId", "execute", "producerStepId", "step", "producerOutputId", "red"))));

    ChatRetainedOutputAdmissionTest() {
        service.setDispatchAuthority(authority); service.setPackagePreparation(packages); service.setSkillGrants(grants);
        when(typed.store()).thenReturn(store);
        var c = new ChatConversationEntity().setId(42L).setJiacn("owner").setConversationType("juyiting")
                .setConversationScopeType("bounty").setConversationScopeKey("task:task").setTaskId("task")
                .setLifecycleGeneration(3L).setTargetAgentIds("[\"agent\"]");
        c.setTenantId("0"); c.setClientId("client");
        when(conversations.findScopedById(any(ConversationDataScope.class), eq("42"))).thenReturn(c);
        when(conversations.lockScopedById(any(ConversationDataScope.class), eq("42"))).thenReturn(c);
        when(scopes.parsePersistedTargetAgentIds(any())).thenReturn(List.of("agent"));
        when(bindings.lock(any(), eq("task"))).thenReturn(new ChatBountyBindingStore.Binding(7, 42L));
        when(mutations.executeWithLockedTaskRootInOwnerScope(eq("0"), eq("client"), eq("owner"), eq("task"), any()))
                .thenAnswer(call -> ((AgentTaskMutationTransaction.LockedTaskMutation<?>) call.getArgument(4))
                        .apply(new AgentTaskMetaEntity().setTaskVersion(7L).setAssignedAgentId("agent")));
        when(authority.admissionTarget("0", "owner", "client", "task", "agent"))
                .thenReturn(new TaskAssigneeAuthorityService.Target("0", "client", "executor", "agent", 1, null));
        var parent = new ChatTypedDeliberationStore.Outcome("outcome", scope, "chat-parent", 1, "parent-turn", "task", 7, 11,
                "b".repeat(64), "ACTION_REQUEST", "red done", "{}", "{}", "{}", "[]", 1);
        when(typed.requireParent(scope, "outcome")).thenReturn(parent);
        when(events.findTurn("0", "owner", "client", "parent-turn")).thenReturn(new ChatTurnEntity().setRoute("CHAT").setState("FINAL_PERSISTED"));
        when(finals.readActionIfPresent(scope, "chat-parent", "parent-turn", 1, "CHAT")).thenReturn(Map.of("state", "READY",
                "outcome", Map.of("outcomeId", "outcome", "finalDigest", "b".repeat(64)),
                "actionProgress", Map.of("state", "COMPLETED", "childRoute", "EXECUTE")));
        when(finals.deliveryTargets(eq(scope), anyMap())).thenReturn(targets);
        when(contexts.effectiveSelectors(any(), eq("task"), eq("TASK_DEFAULT"), anyList(), anyList())).thenReturn(List.of());
        when(contexts.withDeliverySelectors(any(), eq("task"), eq("agent"), eq("TASK_DEFAULT"), eq(List.of()), eq(targets)))
                .thenReturn(List.of(selector));
        when(contexts.resolve(any(), eq("task"), eq("agent"), eq(List.of(selector)))).thenReturn(
                new ChatTypedDeliberationContextService.Context(Map.of(), catalog, List.of(selector), Map.of()));
        var dispatch = new ChatDeliberationService.Dispatch("request", "turn", "dispatch", "event", "agent", "CHAT", "QUEUED", "snapshot", "hash", Map.of(), Map.of());
        when(deliberation.admit(eq("0"), eq(sender), eq("42"), eq(3L), any(), eq(InteractionRoute.CHAT), any(), isNull(), anyMap(), anyMap()))
                .thenReturn(new ChatDeliberationService.Admission("request", 1, "100", "42", 3, InteractionRoute.CHAT, List.of(dispatch), false));
        when(store.insertAdmission(any())).thenReturn(1);
    }

    @Test void discoversBeforeFreezingPersistsExactCatalogAndReplaysWithoutRediscovery() {
        assertFalse(withScope(() -> service.admit("0", sender, "42", "key", command)).replay());
        var order = inOrder(contexts, deliberation, store);
        order.verify(contexts).withDeliverySelectors(any(), eq("task"), eq("agent"), eq("TASK_DEFAULT"), eq(List.of()), eq(targets));
        order.verify(contexts).resolve(any(), eq("task"), eq("agent"), eq(List.of(selector)));
        @SuppressWarnings("unchecked") var facts = ArgumentCaptor.forClass(Map.class);
        order.verify(deliberation).admit(eq("0"), eq(sender), eq("42"), eq(3L), any(), eq(InteractionRoute.CHAT), any(), isNull(), anyMap(), facts.capture());
        var saved = ArgumentCaptor.forClass(ChatTypedDeliberationStore.Admission.class);
        order.verify(store).insertAdmission(saved.capture());
        assertEquals(catalog, saved.getValue().sourceCatalogJson());
        assertEquals(List.of(Map.of("outcomeId", "outcome", "finalDigest", "b".repeat(64), "outputSource", output, "sourceRefId", "source-red")),
                facts.getValue().get("retainedOutputSources"));
        when(store.findAdmissionByKey(eq(scope), eq("key"), anyBoolean())).thenReturn(saved.getValue());
        clearInvocations(contexts, finals, deliberation, packages, grants);
        assertTrue(withScope(() -> service.admit("0", sender, "42", "key", command)).replay());
        verifyNoInteractions(contexts, finals, deliberation, packages, grants);
        var readScopes = ArgumentCaptor.forClass(ConversationDataScope.class);
        verify(conversations, times(2)).findScopedById(readScopes.capture(), eq("42"));
        readScopes.getAllValues().forEach(value -> assertEquals(scope(), value.businessScope()));
        verify(conversations, never()).findScopedById(anyString(), anyString(), anyString());
    }

    @Test void unavailableOutputDoesNotPersistAdmissionOrDispatch() {
        when(contexts.withDeliverySelectors(any(), any(), any(), any(), anyList(), anyList())).thenThrow(
                new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, "not ready"));
        assertThrows(ChatDeliberationException.class,
                () -> withScope(() -> service.admit("0", sender, "42", "key", command)));
        verify(contexts, never()).resolve(any(), any(), any(), anyList());
        verifyNoInteractions(deliberation);
        verify(store, never()).insertAdmission(any());
    }

    private static BusinessScope scope() {
        return new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"), "owner", "client");
    }

    private static <T> T withScope(Supplier<T> work) {
        return TrustedTenantContext.withScope(scope(), work);
    }
}
