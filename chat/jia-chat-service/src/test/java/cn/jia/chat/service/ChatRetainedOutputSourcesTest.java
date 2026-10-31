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

    @Test void combinedSourcesBeyondOldLimitArePreservedWithoutTruncation() {
        var selected = new ArrayList<SourceSelector>();
        for (int i = 0; i < 32; i++) selected.add(new SourceSelector("TASK_LINKED_WORKSPACE_VERSION", "file" + i, "1", "INPUT", null, null));
        var combined=contexts.withDeliverySelectors(scope,"task","agent","TASK_DEFAULT",selected,List.of(retained));
        assertEquals(33,combined.size());assertEquals(selected,combined.subList(0,32));
        assertEquals("CURRENT_CONVERSATION_ASSET",combined.getLast().kind());
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
        verify(capabilities).availableNative(any(), eq("task"), eq(catalog), eq(frozen.frozenDeclaration()), eq("session"));
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
    @Test void workspaceSourceUsesExactAgentPortWithoutChatStorageSql() throws Exception {
        var materials=mock(cn.jia.agent.service.TaskLinkedMaterialReader.class);
        var selection=new cn.jia.agent.service.TaskLinkedMaterialReader.Selection("image",2,"REFERENCE");
        var readerScope=new cn.jia.agent.service.PersonalWorkspaceTaskLinkService.Scope("0","client","owner");
        byte[] bytes={1,2,3};String sha=ChatSkillSourceAccessService.sha(bytes);
        var metadata=new cn.jia.agent.service.TaskLinkedMaterialReader.Metadata("image/png",sha,3);
        when(materials.metadata(readerScope,"task",selection)).thenReturn(metadata);
        when(materials.open(readerScope,"task",selection,metadata)).thenAnswer(i->new java.io.ByteArrayInputStream(bytes));
        var conversations=mock(cn.jia.chat.dao.ChatConversationDao.class);
        var c=new cn.jia.chat.entity.ChatConversationEntity();
        c.setId(42L);c.setTenantId("0");c.setJiacn("owner");c.setClientId("client");c.setLifecycleGeneration(3L);
        c.setTaskId("task");c.setConversationType("juyiting");c.setConversationScopeType("bounty");
        c.setConversationScopeKey("task:task");c.setTargetAgentIds("[\"agent\"]");
        when(conversations.findScopedById("owner","client","42")).thenReturn(c);
        var scopes=mock(JuyitingConversationScopeService.class);
        when(scopes.parsePersistedTargetAgentIds(c.getTargetAgentIds())).thenReturn(List.of("agent"));
        var actual=new ChatSkillSourceAccessService(conversations,scopes,mock(ChatConversationArchiveStore.class),
                mock(PersonalWorkspaceExecutionService.class),mock(cn.jia.agent.service.SkillExecutionService.class),jdbc,materials);
        var identity=Map.<String,Object>of("kind","TASK_LINKED_WORKSPACE_VERSION","fileId","image","version","2","purpose","REFERENCE");
        var input=Map.<String,Object>of("sourceKind","TASK_LINKED_WORKSPACE_VERSION","sourceIdentity",identity,
                "sha256",sha,"byteLength","3","contentMimeType","image/png");
        try(var result=actual.open(owner,"task","42",3,input)) {
            assertEquals("material",result.filename());assertArrayEquals(bytes,result.content().readNBytes(3));
        }
        verify(materials,times(2)).metadata(readerScope,"task",selection);
        verify(materials).open(readerScope,"task",selection,metadata);verifyNoInteractions(jdbc);
        when(materials.metadata(readerScope,"task",selection)).thenThrow(new cn.jia.agent.service.TaskLinkedMaterialReader.Failure(
                cn.jia.agent.service.TaskLinkedMaterialReader.Failure.Reason.UNAVAILABLE));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,assertThrows(ChatDeliberationException.class,
                ()->actual.requireAuthorized(owner,"task","42",3,input)).reason());
    }

    @Test void typedWorkspaceContextFreezesSameIdentityAndMetadataThroughAgentPort() {
        var materials=mock(cn.jia.agent.service.TaskLinkedMaterialReader.class);contexts.setMaterials(materials);
        var authority=mock(ChatDispatchAuthorityService.class);contexts.setDispatchAuthority(authority);
        when(authority.admissionTarget("0","owner","client","task","agent")).thenReturn(
                new TaskAssigneeAuthorityService.Target("0","client","executor","agent",1,null));
        when(schema.ready()).thenReturn(true);
        var profile=Map.<String,Object>of("schemaVersion",1,"supportedInputs",List.of(Map.of(
                "mediaKind","image","mimeType","image/png","carrier","LOCAL_IMAGE","carrierContractDigest","sha256:"+"d".repeat(64))));
        when(sessions.requireSingleReady(new TypedDeliberationSessionRegistry.Scope("0","executor","client"),"agent"))
                .thenReturn(new TypedDeliberationSessionRegistry.Ready("session","agent",List.of(),Map.of("materialsInput",profile)));
        when(capabilities.available(any(),eq("task"),anyList())).thenReturn(List.of());
        var readerScope=new cn.jia.agent.service.PersonalWorkspaceTaskLinkService.Scope("0","client","owner");
        var selection=new cn.jia.agent.service.TaskLinkedMaterialReader.Selection("file",2,"REFERENCE");
        when(materials.metadata(readerScope,"task",selection)).thenReturn(
                new cn.jia.agent.service.TaskLinkedMaterialReader.Metadata("image/png","a".repeat(64),3));
        var selector=new SourceSelector("TASK_LINKED_WORKSPACE_VERSION","file","2","REFERENCE",null,null);
        var frozen=contexts.resolve(scope,"task","agent",List.of(selector));
        var row=ChatTypedDeliberationContextService.parseCatalog(frozen.sourceCatalogJson()).getFirst();
        assertEquals("TASK_WORKSPACE_FILE",row.get("sourceKind"));
        assertEquals(cn.jia.chat.api.ChatTypedDeliberationWire.selectorMap(selector),row.get("sourceIdentity"));
        assertEquals("a".repeat(64),row.get("contentHash"));assertEquals("3",row.get("byteLength"));
        assertEquals(ChatTypedDeliberationContextService.sourceRefId(scope,row),row.get("sourceRefId"));
        verifyNoInteractions(jdbc);
        // Changing the assignment baseline must not make a detached current selection readable.
        when(materials.metadata(readerScope,"task",selection)).thenThrow(
                new cn.jia.agent.service.TaskLinkedMaterialReader.Failure(
                        cn.jia.agent.service.TaskLinkedMaterialReader.Failure.Reason.NOT_FOUND));
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class,
                        ()->contexts.resolve(scope,"task","agent",List.of(selector))).reason());
    }
}
