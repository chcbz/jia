package cn.jia.chat.handler;

import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControlledImageBountyExecutionV3DeclarationTest {
    @Test void exactGenerateAndEditDeclarationNormalizesToReadySibling() throws Exception {
        Object declaration=parse(candidate(true));
        assertEquals(Map.of("state","READY","schemaVersion",1,
                "transport","PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V3",
                "supportedOperations",List.of("GENERATE_IMAGE","EDIT_IMAGE")),normalized(declaration));
        var session=session(declaration,"runtime-a");
        assertEquals(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,
                session.state());
        assertEquals("runtime-a",session.runtimeInstanceId());
        assertEquals(List.of("GENERATE_IMAGE","EDIT_IMAGE"),session.operations());
    }

    @Test void disabledIsEmptyAndEveryShapeOrManifestDriftIsUnsupported() throws Exception {
        Object disabled=parse(candidate(false));
        assertEquals(Map.of("state","DISABLED"),normalized(disabled));
        assertEquals(List.of(),session(disabled,"runtime-a").operations());

        var cases=new java.util.ArrayList<Map<String,Object>>();
        var extra=new LinkedHashMap<>(candidate(true));extra.put("extra",true);cases.add(extra);
        var oldCommand=new LinkedHashMap<>(candidate(true));oldCommand.put("commandSchemaVersions",List.of(2));cases.add(oldCommand);
        var missingEdit=new LinkedHashMap<>(candidate(true));missingEdit.put("operations",List.of(operation("GENERATE_IMAGE")));cases.add(missingEdit);
        var duplicate=new LinkedHashMap<>(candidate(true));duplicate.put("operations",List.of(operation("GENERATE_IMAGE"),operation("GENERATE_IMAGE")));cases.add(duplicate);
        var wrongSource=new LinkedHashMap<>(candidate(true));
        @SuppressWarnings("unchecked") var operations=(List<Map<String,Object>>)wrongSource.get("operations");
        var first=new LinkedHashMap<>(operations.getFirst());
        first.put("inputManifest",Map.of("schemaVersion",3,"minItems",0,"maxItems",16,
                "mimeTypes",List.of("image/jpeg","image/png"),
                "sourceKinds",List.of("CURRENT_CONVERSATION_ASSET")));
        wrongSource.put("operations",List.of(first,operations.get(1)));cases.add(wrongSource);
        for(Map<String,Object> malformed:cases) {
            Object value=parse(malformed);
            assertEquals(Map.of("state","UNSUPPORTED"),normalized(value),malformed.toString());
            assertEquals(List.of(),session(value,"runtime-a").operations(),malformed.toString());
        }
    }

    static Map<String,Object> candidate(boolean enabled) {
        Map<String,Object> root=new LinkedHashMap<>();root.put("schemaVersion",1);root.put("enabled",enabled);
        root.put("transport","PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V3");
        root.put("commandSchemaVersions",List.of(3));root.put("leaseProtocolVersions",List.of(1));
        root.put("providerStartFenceVersions",List.of(3));root.put("resultCommitProtocolVersions",List.of(1));
        root.put("operations",enabled?List.of(operation("GENERATE_IMAGE"),operation("EDIT_IMAGE")):List.of());
        return root;
    }

    private static Map<String,Object> operation(String operation) {
        boolean edit="EDIT_IMAGE".equals(operation);
        return Map.of("operation",operation,
                "inputManifest",Map.of("schemaVersion",3,"minItems",edit?1:0,"maxItems",edit?1:16,
                        "mimeTypes",List.of("image/jpeg","image/png"),
                        "sourceKinds",List.of(edit?"CURRENT_CONVERSATION_ASSET":"TASK_LINKED_WORKSPACE_VERSION")),
                "resultManifest",Map.of("schemaVersion",1,"minItems",1,"maxItems",1,
                        "outputId","output_1","mimeTypes",List.of("image/png")));
    }

    private static Object parse(Object value) throws Exception {
        Class<?> type=Class.forName("cn.jia.chat.handler.AgentWebSocketHandler$ControlledImageV3Declaration");
        Method method=type.getDeclaredMethod("parse",Object.class);method.setAccessible(true);
        return method.invoke(null,value);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> normalized(Object value) throws Exception {
        Method method=value.getClass().getDeclaredMethod("normalizedForReceipt");method.setAccessible(true);
        return (Map<String,Object>)method.invoke(value);
    }
    private static ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration session(
            Object value,String runtime) throws Exception {
        Method method=value.getClass().getDeclaredMethod("session",String.class);method.setAccessible(true);
        return (ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration)
                method.invoke(value,runtime);
    }
}
