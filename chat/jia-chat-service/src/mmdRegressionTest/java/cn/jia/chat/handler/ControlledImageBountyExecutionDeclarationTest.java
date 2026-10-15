package cn.jia.chat.handler;

import cn.jia.agent.service.ControlledImageExecutionSessionLookup;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ControlledImageBountyExecutionDeclarationTest {
    @Test void exactReadyDeclarationIsAccepted() {
        var value=ControlledImageBountyExecutionDeclaration.parse(candidate(true));
        assertEquals(ControlledImageExecutionSessionLookup.State.READY,value.state());
        assertEquals(Map.of("state","READY","schemaVersion",1,
                "transport",ControlledImageBountyExecutionDeclaration.TRANSPORT,
                "supportedOperations",List.of("GENERATE_IMAGE")),value.normalizedForReceipt());
    }
    @Test void disabledMustHaveNoOperationsAndMalformedNeverBecomesReady() {
        assertEquals(ControlledImageExecutionSessionLookup.State.DISABLED,
                ControlledImageBountyExecutionDeclaration.parse(candidate(false)).state());
        var extra=new LinkedHashMap<>(candidate(true));extra.put("extra",true);
        assertEquals(ControlledImageExecutionSessionLookup.State.UNSUPPORTED,
                ControlledImageBountyExecutionDeclaration.parse(extra).state());
        var wrong=new LinkedHashMap<>(candidate(true));wrong.put("commandSchemaVersions",List.of(1));
        assertEquals(ControlledImageExecutionSessionLookup.State.UNSUPPORTED,
                ControlledImageBountyExecutionDeclaration.parse(wrong).state());
    }
    static Map<String,Object> candidate(boolean enabled) {
        Map<String,Object> root=new LinkedHashMap<>();root.put("schemaVersion",1);root.put("enabled",enabled);
        root.put("transport",ControlledImageBountyExecutionDeclaration.TRANSPORT);
        root.put("commandSchemaVersions",List.of(2));root.put("leaseProtocolVersions",List.of(1));
        root.put("providerStartFenceVersions",List.of(2));root.put("resultCommitProtocolVersions",List.of(1));
        if(!enabled){root.put("operations",List.of());return root;}
        root.put("operations",List.of(Map.of("operation","GENERATE_IMAGE",
                "inputManifest",Map.of("schemaVersion",1,"minItems",0,"maxItems",16,
                        "mimeTypes",List.of("image/jpeg","image/png")),
                "resultManifest",Map.of("schemaVersion",1,"minItems",1,"maxItems",1,
                        "outputId","output_1","mimeTypes",List.of("image/png")))));
        return root;
    }
}
