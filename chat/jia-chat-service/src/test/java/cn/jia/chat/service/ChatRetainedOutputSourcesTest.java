package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.TaskAssigneeAuthorityService;
import cn.jia.chat.api.ChatTypedDeliberationWire.SourceSelector;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.config.ChatTypedDeliberationReadiness;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Retained output -> authorized input -> frozen model-visible lineage, without a model/provider. */
class ChatRetainedOutputSourcesTest {
    final ChatTypedDeliberationContextService.Scope scope =
            new ChatTypedDeliberationContextService.Scope("0", "owner", "client", "42", 3);
    final PersonalWorkspaceExecutionService.OwnerScope owner =
            new PersonalWorkspaceExecutionService.OwnerScope("0", "client", "owner");
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ChatSkillSourceAccessService sources = mock(ChatSkillSourceAccessService.class);
    final TypedDeliberationSessionRegistry sessions = mock(TypedDeliberationSessionRegistry.class);
    final ChatTypedDeliberationReadiness schema = mock(ChatTypedDeliberationReadiness.class);
    final ChatActionCapabilityService capabilities = mock(ChatActionCapabilityService.class);
    final ChatTypedDeliberationContextService contexts =
            new ChatTypedDeliberationContextService(jdbc, sessions, schema, capabilities, true);
    final Map<String,Object> output = Map.of("requestId", "producer", "stepId", "step", "outputId", "red", "sha256", "a".repeat(64));
    final Map<String,Object> retained = Map.of("outcomeId", "outcome", "finalDigest", "b".repeat(64), "outputSource", output);
    final ChatConversationArchiveStore.Source red = new ChatConversationArchiveStore.Source(
            "asset-red", 1, "42", 3, "producer", 1, "step", "task", "execution", "run", "red", "image/png", "a".repeat(64), 10);
    final SourceSelector redSelector = new SourceSelector("CURRENT_CONVERSATION_ASSET", null, null, null, "asset-red", "1");

    ChatRetainedOutputSourcesTest() {
        contexts.setSkillSources(sources);
        when(sources.outputAsset(owner, "task", "42", 3, "agent", output)).thenReturn(red);
    }

    @Test void taskDefaultAddsRetainedOutputWithoutDroppingOriginalReferences() {
        var original = new SourceSelector("TASK_LINKED_WORKSPACE_VERSION", "blue", "1", "INPUT", null, null);
        assertEquals(List.of(original, redSelector), contexts.withDeliverySelectors(scope, "task", "agent", "TASK_DEFAULT",
                List.of(original), List.of(retained)));
        verifyNoInteractions(jdbc); // no arbitrary workspace/history query and no projector writes
    }

    @Test void explicitAndNoneNeverDiscoverOrExpandSources() {
        assertEquals(List.of(redSelector), contexts.withDeliverySelectors(scope, "task", "agent", "EXPLICIT", List.of(redSelector), List.of(retained)));
        assertEquals(List.of(), contexts.withDeliverySelectors(scope, "task", "agent", "NONE", List.of(), List.of(retained)));
        verifyNoInteractions(sources, jdbc);
    }

    @Test void clarificationInheritedOrExplicitSameAssetIsNotDuplicated() {
        assertEquals(List.of(redSelector), contexts.withDeliverySelectors(scope, "task", "agent", "TASK_DEFAULT", List.of(redSelector), List.of(retained)));
    }

    @Test void textOnlyParentDoesNotInventAMediaSource() {
        assertTrue(contexts.withDeliverySelectors(scope, "task", "agent", "TASK_DEFAULT", List.of(),
                List.of(Map.of("outcomeId", "text", "finalDigest", "c".repeat(64), "text", "done"))).isEmpty());
        verifyNoInteractions(sources, jdbc);
    }

    @Test void missingProjectionFailsBeforeFreezingInsteadOfReturningOriginalOnly() {
        when(sources.outputAsset(owner, "task", "42", 3, "agent", output)).thenThrow(
                new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, "not ready"));
        var error = assertThrows(ChatDeliberationException.class, () -> contexts.withDeliverySelectors(scope, "task", "agent", "TASK_DEFAULT",
                List.of(new SourceSelector("TASK_LINKED_WORKSPACE_VERSION", "blue", "1", "INPUT", null, null)), List.of(retained)));
        assertEquals(ChatDeliberationException.Reason.CONFLICT, error.reason());
        verifyNoInteractions(capabilities, sessions);
    }

    @Test void existingSourceCountLimitIsCheckedAfterCombiningNotSilentlyTruncated() {
        var selected = new ArrayList<SourceSelector>();
        for (int i = 0; i < 32; i++) selected.add(new SourceSelector("TASK_LINKED_WORKSPACE_VERSION", "file" + i, "1", "INPUT", null, null));
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST, assertThrows(ChatDeliberationException.class,
                () -> contexts.withDeliverySelectors(scope, "task", "agent", "TASK_DEFAULT", selected, List.of(retained))).reason());
    }

    @Test void realResolveFreezesExactOutputShaAndFeedsNativeCarrierAndCapabilityCatalog() {
        var authority = mock(ChatDispatchAuthorityService.class);
        contexts.setDispatchAuthority(authority);
        when(authority.admissionTarget("0", "owner", "client", "task", "agent")).thenReturn(
                new TaskAssigneeAuthorityService.Target("0", "client", "executor", "agent", 1, null));
        when(schema.ready()).thenReturn(true);
        var profile = Map.<String,Object>of("schemaVersion", 1, "supportedInputs", List.of(Map.of(
                "mediaKind", "image", "mimeType", "image/png", "carrier", "LOCAL_IMAGE", "carrierContractDigest", "sha256:" + "d".repeat(64))));
        when(sessions.requireSingleReady(new TypedDeliberationSessionRegistry.Scope("0", "executor", "client"), "agent"))
                .thenReturn(new TypedDeliberationSessionRegistry.Ready("session", "agent", List.of(), Map.of("materialsInput", profile)));
        when(sources.asset(eq(owner), eq("task"), eq("42"), eq(3L), eq("agent"), anyMap())).thenReturn(red);
        when(capabilities.available(any(), eq("task"), anyList())).thenReturn(List.of());
        var selected = contexts.withDeliverySelectors(scope, "task", "agent", "TASK_DEFAULT", List.of(), List.of(retained));
        var frozen = contexts.resolve(scope, "task", "agent", selected);
        var catalog = ChatTypedDeliberationContextService.parseCatalog(frozen.sourceCatalogJson());
        assertEquals(1, catalog.size());
        assertEquals(red.sha256(), catalog.getFirst().get("contentHash"));
        assertEquals(ChatSkillSourceAccessService.assetIdentity(red), catalog.getFirst().get("sourceIdentity"));
        var bindings = ChatTypedDeliberationContextService.retainedOutputSources(frozen, List.of(retained));
        assertEquals(List.of(Map.of("outcomeId", "outcome", "finalDigest", "b".repeat(64), "outputSource", output,
                "sourceRefId", catalog.getFirst().get("sourceRefId"))), bindings);
        var publicSource = (Map<?,?>) ((List<?>) frozen.facts().get("availableSources")).getFirst();
        assertEquals(Set.of("sourceRefId", "kind", "mediaType", "contentMimeType"), publicSource.keySet());
        var carrier = (Map<?,?>) ((List<?>) ChatMaterialsInputContract.candidates(frozen.frozenDeclaration(), catalog).get("sources")).getFirst();
        assertEquals(red.sha256(), carrier.get("sha256"));
        assertEquals(bindings.getFirst().get("sourceRefId"), carrier.get("sourceRefId"));
        verify(capabilities).available(any(), eq("task"), eq(catalog));
    }

    @Test void multipleOutputsAreMappedByFullIdentityNotPositionAndStaleShaCannotMatch() {
        var second = new ChatConversationArchiveStore.Source("asset-other", 1, "42", 3, "producer", 1, "step", "task",
                "execution", "run", "other", "image/png", "c".repeat(64), 10);
        var otherOutput = Map.<String,Object>of("requestId", "producer", "stepId", "step", "outputId", "other", "sha256", second.sha256());
        var other = Map.<String,Object>of("outcomeId", "outcome", "finalDigest", "b".repeat(64), "outputSource", otherOutput);
        var frozen = context(List.of(entry(second, "source-other"), entry(red, "source-red")));
        var bindings = ChatTypedDeliberationContextService.retainedOutputSources(frozen, List.of(retained, other));
        assertEquals(List.of("source-red", "source-other"), bindings.stream().map(x -> x.get("sourceRefId")).toList());
        var stale = Map.<String,Object>of("outcomeId", "outcome", "finalDigest", "b".repeat(64), "outputSource",
                Map.of("requestId", "producer", "stepId", "step", "outputId", "red", "sha256", "e".repeat(64)));
        assertTrue(ChatTypedDeliberationContextService.retainedOutputSources(frozen, List.of(stale)).isEmpty());
        assertTrue(ChatTypedDeliberationContextService.retainedOutputSources(context(List.of()), List.of(retained)).isEmpty());
    }

    private static ChatTypedDeliberationContextService.Context context(List<Map<String,Object>> catalog) {
        return new ChatTypedDeliberationContextService.Context(Map.of(), CanonicalContextJson.write(catalog), List.of(), Map.of());
    }
    private static Map<String,Object> entry(ChatConversationArchiveStore.Source source, String ref) {
        return Map.of("sourceKind", "CURRENT_CONVERSATION_ASSET", "sourceIdentity", ChatSkillSourceAccessService.assetIdentity(source),
                "sourceRefId", ref, "contentHash", source.sha256());
    }
}
