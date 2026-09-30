package cn.jia.chat.handler;

import cn.jia.agent.service.NativeBountyExecutionSessionLookup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativeBountyExecutionDeclarationTest {
    @Test
    void exactGenerateImageDeclarationIsReadyWithoutChangingFastExecute() {
        var snapshot = NativeBountyExecutionDeclaration.parse(candidate(true)).snapshot();
        assertEquals(NativeBountyExecutionSessionLookup.State.READY, snapshot.state());
        assertEquals(1, snapshot.schemaVersion());
        assertEquals(NativeBountyExecutionDeclaration.TRANSPORT, snapshot.transport());
        assertEquals(List.of("GENERATE_IMAGE"), snapshot.supportedOperations());
        assertEquals(AgentRuntimeCapabilities.Decision.UNSUPPORTED,
                AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true, true))
                        .decision(cn.jia.chat.deliberation.InteractionRoute.EXECUTE));
    }

    @Test
    void missingDisabledAndMalformedDeclarationsRemainDistinct() {
        assertEquals(NativeBountyExecutionSessionLookup.State.UNDECLARED,
                NativeBountyExecutionDeclaration.parse(null).snapshot().state());
        var disabled = NativeBountyExecutionDeclaration.parse(candidate(false)).snapshot();
        assertEquals(NativeBountyExecutionSessionLookup.State.DISABLED, disabled.state());
        assertNull(disabled.transport());

        for (Map<String,Object> malformed : List.of(
                changed("schemaVersion", 2), changed("transport", "D03"),
                changed("commandSchemaVersions", List.of(2)),
                changed("operations", List.of()), withUnknownKey(), duplicateOperation(),
                changedOperation("EDIT_IMAGE"), changedInputMax(31), changedOutput("output_2"))) {
            assertEquals(NativeBountyExecutionSessionLookup.State.UNSUPPORTED,
                    NativeBountyExecutionDeclaration.parse(malformed).snapshot().state());
        }
    }

    static Map<String,Object> candidate(boolean enabled) {
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1); root.put("enabled", enabled);
        root.put("transport", NativeBountyExecutionDeclaration.TRANSPORT);
        root.put("commandSchemaVersions", List.of(1)); root.put("leaseProtocolVersions", List.of(1));
        root.put("providerStartFenceVersions", List.of(1)); root.put("resultCommitProtocolVersions", List.of(1));
        root.put("operations", enabled ? List.of(operation("GENERATE_IMAGE", 32, "output_1")) : List.of());
        return root;
    }
    private static Map<String,Object> operation(String operation, int max, String output) {
        return Map.of("operation", operation,
                "inputManifest", Map.of("schemaVersion",1,"minItems",0,"maxItems",max,
                        "mimeTypes",List.of("image/jpeg","image/png")),
                "resultManifest", Map.of("schemaVersion",1,"minItems",1,"maxItems",1,
                        "outputId",output,"mimeTypes",List.of("image/png")));
    }
    private static Map<String,Object> changed(String key,Object value) {
        Map<String,Object> result=new LinkedHashMap<>(candidate(true));result.put(key,value);return result;
    }
    private static Map<String,Object> withUnknownKey() {
        Map<String,Object> result=new LinkedHashMap<>(candidate(true));result.put("ability",true);return result;
    }
    private static Map<String,Object> duplicateOperation() {
        return changed("operations",List.of(operation("GENERATE_IMAGE",32,"output_1"),operation("GENERATE_IMAGE",32,"output_1")));
    }
    private static Map<String,Object> changedOperation(String operation) {
        return changed("operations",List.of(operation(operation,32,"output_1")));
    }
    private static Map<String,Object> changedInputMax(int max) {
        return changed("operations",List.of(operation("GENERATE_IMAGE",max,"output_1")));
    }
    private static Map<String,Object> changedOutput(String output) {
        return changed("operations",List.of(operation("GENERATE_IMAGE",32,output)));
    }
}
