package cn.jia.chat.service;

import cn.jia.agent.service.ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup;
import cn.jia.agent.service.ControlledImageProviderAuthorityLookup;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatActionCapabilityServiceTest {
    private final TypedInspectionSessionRegistry inspections = new TypedInspectionSessionRegistry();
    @SuppressWarnings("unchecked") private final ObjectProvider<RuntimeDeclarationLookup> providers = mock(ObjectProvider.class);
    private final RuntimeDeclarationLookup executions = mock(RuntimeDeclarationLookup.class);
    private final ControlledImageProviderAuthorityLookup authorities = mock(ControlledImageProviderAuthorityLookup.class);
    private final ChatActionCapabilityService.Scope scope = new ChatActionCapabilityService.Scope("0", "owner", "client", "agent");
    private ChatActionCapabilityService service() {
        return new ChatActionCapabilityService(inspections, providers, authorities, true, true, true, true);
    }
    private static Map<String,Object> source(String media, String mime) {
        return Map.of("mediaType", media, "contentMimeType", mime);
    }
    @Test void noLiveAdapterMeansNoActionsNotImaginaryImageSupport() {
        assertEquals(List.of(), service().available(scope, List.of(source("image", "image/png"))));
        verifyNoInteractions(executions, authorities);
    }
    @Test void inspectionUsesActualMimeSupportAndDoesNotGrantExecution() {
        register("session", "owner");
        var actions = service().available(scope, List.of(source("text","text/plain"), source("file","application/zip")));
        assertEquals(1, actions.size());
        assertEquals(Map.of("actionId","inspect-materials","kind","INSPECT_INPUTS","operation","INSPECT_INPUTS",
                "inputMediaTypes",List.of("text"),"minSources",1,"maxSources",32), actions.getFirst());
        assertEquals(List.of(), service().available(scope, List.of(source("file","application/zip"))));
        assertEquals(List.of(), service().available(scope, List.of()));
        verifyNoInteractions(executions, authorities);
    }
    @Test void foreignOrAmbiguousOrDisconnectedInspectorIsNotAdvertised() {
        register("foreign", "another-owner");
        assertEquals(List.of(), service().available(scope, List.of(source("text","text/plain"))));
        register("one", "owner"); register("two", "owner");
        assertEquals(List.of(), service().available(scope, List.of(source("text","text/plain"))));
        inspections.remove("two"); inspections.remove("one");
        assertEquals(List.of(), service().available(scope, List.of(source("text","text/plain"))));
    }
    @Test void readyImageAdapterRequiresExactCurrentOperatorBindingAndKeepsItsOwnLimits() {
        readyImage();
        var actions = service().available(scope, List.of());
        assertEquals(List.of("generate-image","edit-image"), actions.stream().map(a->a.get("actionId")).toList());
        assertEquals(0, actions.getFirst().get("minSources")); assertEquals(1, actions.getLast().get("minSources"));
        for(var action:actions) { assertEquals(16,action.get("maxSources")); assertEquals(List.of("image"),action.get("inputMediaTypes")); }
        assertDoesNotThrow(()->ChatActionOutcomeContract.facts(Map.of("schemaVersion",3,"availableActions",actions,
                "availableSources",List.of(),"inspectedSourceRefIds",List.of())));
        verify(executions).current(new RuntimeDeclarationLookup.DeclarationScope("0","client","owner","agent"));
        verify(authorities).current(new ControlledImageProviderAuthorityLookup.Scope("0","client","owner"),"agent","binding",2L);
        assertThrows(UnsupportedOperationException.class,()->actions.add(Map.of()));
        assertFalse(actions.toString().contains("model-actual"));
    }
    @Test void declaredPlannerOrStaleBindingCannotCreateExecutionAuthority() {
        readyImage();
        when(authorities.current(any(), anyString(), anyString(), anyLong())).thenReturn(
                new ControlledImageProviderAuthorityLookup.Snapshot(ControlledImageProviderAuthorityLookup.State.READY,
                        "CONTROLLED_IMAGE_HTTP_V1","binding","3","model-actual",1));
        assertEquals(List.of(),service().available(scope,List.of()));
        when(authorities.current(any(), anyString(), anyString(), anyLong())).thenReturn(
                new ControlledImageProviderAuthorityLookup.Snapshot(ControlledImageProviderAuthorityLookup.State.UNAVAILABLE,null,null,null,null,null));
        assertEquals(List.of(),service().available(scope,List.of()));
    }
    @Test void disabledRuntimeFlagsDoNotQueryPaidAdapter() {
        var disabled = new ChatActionCapabilityService(inspections,providers,authorities,false,false,true,true);
        assertEquals(List.of(),disabled.available(scope,List.of(source("image","image/png"))));
        verifyNoInteractions(providers,executions,authorities);
    }
    @Test void unsupportedAdapterBoundsAreNotConvertedToGeneric32() {
        readyImage();
        when(executions.current(any())).thenReturn(new RuntimeDeclarationLookup.Declaration(RuntimeDeclarationLookup.State.READY,
                "runtime",List.of("GENERATE_IMAGE","EDIT_IMAGE"),"CONTROLLED_IMAGE_HTTP_V1","binding",2L,"model-actual",32,1,1));
        assertEquals(List.of(),service().available(scope,List.of()));
        verifyNoInteractions(authorities);
    }
    private void readyImage() {
        when(providers.getIfUnique()).thenReturn(executions);
        when(executions.current(any())).thenReturn(new RuntimeDeclarationLookup.Declaration(RuntimeDeclarationLookup.State.READY,
                "runtime",List.of("GENERATE_IMAGE","EDIT_IMAGE"),"CONTROLLED_IMAGE_HTTP_V1","binding",2L,"model-actual",16,1,1));
        when(authorities.current(any(),anyString(),anyString(),anyLong())).thenReturn(
                new ControlledImageProviderAuthorityLookup.Snapshot(ControlledImageProviderAuthorityLookup.State.READY,
                        "CONTROLLED_IMAGE_HTTP_V1","binding","2","model-actual",1));
    }
    private void register(String session, String owner) {
        String digest="sha256:"+"a".repeat(64);
        inspections.register(session,"0",owner,"client","agent",Map.ofEntries(
                Map.entry("schemaVersion",1),Map.entry("contract","juyiting-typed-inspection-v1"),Map.entry("enabled",true),
                Map.entry("profileId","inspection"),Map.entry("engineContractId","native"),Map.entry("enginePolicyDigest",digest),
                Map.entry("toolPolicyDigest",digest),Map.entry("inputPolicyDigest",digest),Map.entry("toolPolicy","MANIFEST_READ_ONLY"),
                Map.entry("recovery","durable-inbox-turn-readback-v1"),Map.entry("supportedInputs",List.of(Map.of(
                        "mediaKind","text","mimeType","text/plain","carrier","DIRECT_TEXT","carrierContractDigest",digest)))),()->true);
    }
}
