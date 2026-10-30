package cn.jia.chat.handler;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TypedDeliberationNativeDeclarationTest {
    private Map<String,Object> declaration() {
        var value=new LinkedHashMap<String,Object>();
        value.put("schemaVersion",5); value.put("state","READY"); value.put("carrier","CHAT_MESSAGE_FINAL_SIDECAR_V5");
        value.put("referenceModes",List.of("NONE","AVAILABLE"));
        value.put("outcomeKinds",List.of("ANSWER","CLARIFY","ACTION_REQUEST","REVISION_REQUEST","SUBMISSION_REQUEST"));
        value.put("engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA");value.put("strictNoToolsVerified",false);
        value.put("toolPolicy","read-only-constrained");return value;
    }
    @Test void declarationIsOptionalStrictAndFrozen() {
        var value=declaration();
        assertEquals(TypedDeliberationDeclaration.State.READY,TypedDeliberationDeclaration.parse(value).state());
        var nativeExecution=new LinkedHashMap<String,Object>();
        nativeExecution.put("schemaVersion",1);nativeExecution.put("executionProtocolVersion",5);
        nativeExecution.put("toolPolicyRef","NATIVE_LOCAL_FILES_V1");nativeExecution.put("outputMimeTypes",List.of("text/plain"));
        value.put("nativeExecution",nativeExecution);
        var parsed=TypedDeliberationDeclaration.parse(value);
        assertEquals(TypedDeliberationDeclaration.State.READY,parsed.state());
        nativeExecution.put("supportedToolPolicyRefs",List.of("NATIVE_SKILL_SCRIPT_V1"));
        assertEquals(List.of("NATIVE_SKILL_SCRIPT_V1"),((Map<?,?>)TypedDeliberationDeclaration.parse(value).frozenReceipt().get("nativeExecution")).get("supportedToolPolicyRefs"));
        nativeExecution.put("supportedToolPolicyRefs",List.of("unknown"));
        assertEquals(TypedDeliberationDeclaration.State.UNSUPPORTED,TypedDeliberationDeclaration.parse(value).state());
        nativeExecution.remove("supportedToolPolicyRefs");
        nativeExecution.put("outputMimeTypes",List.of("text/plain","text/plain"));
        assertEquals(List.of("text/plain"),((Map<?,?>)parsed.frozenReceipt().get("nativeExecution")).get("outputMimeTypes"));
        assertEquals(TypedDeliberationDeclaration.State.UNSUPPORTED,TypedDeliberationDeclaration.parse(value).state());
        nativeExecution.put("outputMimeTypes",List.of("image/png"));
        assertEquals(TypedDeliberationDeclaration.State.READY,TypedDeliberationDeclaration.parse(value).state());
        var office=List.of("text/plain","image/png",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        nativeExecution.put("outputMimeTypes",office);
        var officeParsed=TypedDeliberationDeclaration.parse(value);
        assertEquals(TypedDeliberationDeclaration.State.READY,officeParsed.state());
        assertEquals(office,((Map<?,?>)officeParsed.frozenReceipt().get("nativeExecution")).get("outputMimeTypes"));
        nativeExecution.put("outputMimeTypes",List.of("image/jpeg"));
        assertEquals(TypedDeliberationDeclaration.State.UNSUPPORTED,TypedDeliberationDeclaration.parse(value).state());
    }
}
