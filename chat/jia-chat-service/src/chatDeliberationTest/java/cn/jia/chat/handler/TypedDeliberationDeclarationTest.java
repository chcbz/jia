package cn.jia.chat.handler;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TypedDeliberationDeclarationTest {
    static Map<String,Object> declaration(String state) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("schemaVersion",1);value.put("state",state);
        value.put("carrier","CHAT_MESSAGE_FINAL_SIDECAR_V1");
        value.put("referenceModes",List.of("NONE","AVAILABLE"));
        value.put("outcomeKinds",List.of("ANSWER","CLARIFY","EXECUTION_PROPOSAL"));
        value.put("engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA");
        value.put("strictNoToolsVerified",false);value.put("toolPolicy","read-only-constrained");
        return value;
    }
    @Test void exactReadyDeclarationEnablesOnlyFrozenOperations() {
        var parsed=TypedDeliberationDeclaration.parse(declaration("READY"));
        assertEquals(TypedDeliberationDeclaration.State.READY,parsed.state());
        assertEquals(List.of("GENERATE_IMAGE","EDIT_IMAGE"),parsed.supportedOperations());
        assertEquals(declaration("READY"),parsed.frozenReceipt());
    }
    @Test void unavailableIsValidButNeverReady() {
        var parsed=TypedDeliberationDeclaration.parse(declaration("UNAVAILABLE"));
        assertEquals(TypedDeliberationDeclaration.State.UNAVAILABLE,parsed.state());
        assertTrue(parsed.supportedOperations().isEmpty());
        assertEquals(declaration("UNAVAILABLE"),parsed.frozenReceipt());
    }
    @Test void absentAndAnyShapeDriftAreNotCapabilities() {
        assertEquals(TypedDeliberationDeclaration.State.UNDECLARED,TypedDeliberationDeclaration.parse(null).state());
        Map<String,Object> extra=declaration("READY");extra.put("operations",List.of("GENERATE_IMAGE"));
        assertEquals(TypedDeliberationDeclaration.State.UNSUPPORTED,TypedDeliberationDeclaration.parse(extra).state());
        Map<String,Object> weakened=declaration("READY");weakened.put("strictNoToolsVerified",true);
        assertEquals(TypedDeliberationDeclaration.State.UNSUPPORTED,TypedDeliberationDeclaration.parse(weakened).state());
    }
}
