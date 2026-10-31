package cn.jia.chat.handler;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TypedDeliberationDeclarationTest {
    static Map<String,Object> declaration(String state) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("schemaVersion",5);value.put("state",state);
        value.put("carrier","CHAT_MESSAGE_FINAL_SIDECAR_V5");
        value.put("referenceModes",List.of("NONE","AVAILABLE"));
        value.put("outcomeKinds",List.of("ANSWER","CLARIFY","ACTION_REQUEST","REVISION_REQUEST","SUBMISSION_REQUEST"));
        value.put("engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA");
        value.put("strictNoToolsVerified",false);value.put("toolPolicy","read-only-constrained");
        return value;
    }
    @Test void exactReadyDeclarationAdvertisesPlanningNotImageOrPaidCapabilities() {
        var parsed=TypedDeliberationDeclaration.parse(declaration("READY"));
        assertEquals(TypedDeliberationDeclaration.State.READY,parsed.state());
        assertTrue(parsed.supportedOperations().isEmpty());
        assertEquals(declaration("READY"),parsed.frozenReceipt());
    }
    @Test void unavailableIsValidButNeverReady() {
        var parsed=TypedDeliberationDeclaration.parse(declaration("UNAVAILABLE"));
        assertEquals(TypedDeliberationDeclaration.State.UNAVAILABLE,parsed.state());
        assertTrue(parsed.supportedOperations().isEmpty());
        assertEquals(declaration("UNAVAILABLE"),parsed.frozenReceipt());
    }
    @Test void extraMetadataGrantsNoAuthorityAndExecutionSemanticDriftIsRejected() {
        assertEquals(TypedDeliberationDeclaration.State.UNDECLARED,TypedDeliberationDeclaration.parse(null).state());
        Map<String,Object> extra=declaration("READY");extra.put("operations",List.of("GENERATE_IMAGE"));
        var parsed=TypedDeliberationDeclaration.parse(extra);
        assertEquals(TypedDeliberationDeclaration.State.READY,parsed.state());
        assertTrue(parsed.supportedOperations().isEmpty());assertFalse(parsed.frozenReceipt().containsKey("operations"));
        Map<String,Object> weakened=declaration("READY");weakened.put("strictNoToolsVerified",true);
        assertEquals(TypedDeliberationDeclaration.State.UNSUPPORTED,TypedDeliberationDeclaration.parse(weakened).state());
    }
    @Test void v5NativeDeclarationSurvivesRealSessionRegistryIntoOriginalChatMaterialFacts() {
        var profile=Map.<String,Object>of("schemaVersion",1,"supportedInputs",List.of(Map.of("mediaKind","image","mimeType","image/png","carrier","LOCAL_IMAGE","carrierContractDigest","sha256:"+"a".repeat(64))));
        var declaration=declaration("READY");declaration.put("materialsInput",profile);
        var sessions=new TypedDeliberationSessionRegistry();sessions.register("session","0","owner","client","agent",declaration);
        var registered=sessions.requireSingleReady(new TypedDeliberationSessionRegistry.Scope("0","owner","client"),"agent");
        assertEquals(profile,registered.frozenDeclaration().get("materialsInput"));assertTrue(registered.supportedOperations().isEmpty());
        var sources=List.of(Map.<String,Object>of("sourceRefId","image","sourceKind","TASK_WORKSPACE_FILE","contentHash","b".repeat(64),"byteLength","4","contentMimeType","image/png"),
                Map.<String,Object>of("sourceRefId","unsupported","sourceKind","TASK_WORKSPACE_FILE","contentHash","c".repeat(64),"byteLength","5","contentMimeType","application/pdf"));
        var candidates=cn.jia.chat.service.ChatMaterialsInputContract.candidates(registered.frozenDeclaration(),sources);
        var facts=cn.jia.chat.service.ChatMaterialsInputContract.bind(candidates,"0","owner","client","1",1,"task","request","turn","agent");
        assertEquals(List.of("image"),cn.jia.chat.service.ChatMaterialsInputContract.refs(facts));assertEquals(2,sources.size());
        assertTrue(facts.toString().contains("LOCAL_IMAGE"));
        sessions.remove("session");assertThrows(IllegalStateException.class,()->sessions.requireSingleReady(new TypedDeliberationSessionRegistry.Scope("0","owner","client"),"agent"));
    }
    @Test void legacyDeclarationDoesNotSilentlyNegotiateNewRequests() {
        var old = declaration("READY"); old.put("schemaVersion",1);
        old.put("carrier","CHAT_MESSAGE_FINAL_SIDECAR_V1");
        old.put("outcomeKinds",List.of("ANSWER","CLARIFY","EXECUTION_PROPOSAL"));
        assertEquals(TypedDeliberationDeclaration.State.UNSUPPORTED,TypedDeliberationDeclaration.parse(old).state());
    }

}
