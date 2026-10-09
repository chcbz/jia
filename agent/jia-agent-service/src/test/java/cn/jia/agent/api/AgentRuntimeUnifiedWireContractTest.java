package cn.jia.agent.api;

import cn.jia.agent.entity.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Owner wire tests: synthetic fixtures only, never runtime secrets or environment. */
class AgentRuntimeUnifiedWireContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode fixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/ur02/unified-runtime-wire-v2.redacted.json")) {
            assertNotNull(input);
            return mapper.readTree(input);
        }
    }
    @Test void enrollmentUsesOnlyNestedInstallationAndIndependentPrivateAuthorization() throws Exception {
        var node = fixture().path("enrollment").path("response");
        var value = mapper.treeToValue(node, AgentRuntimeV1EnrollmentResult.class);
        assertWireEquals(node, mapper.valueToTree(value));
        assertEquals(Set.of("installation", "runtimeAuthorization"), fields(node));
        assertFalse(node.has("installationId"));
        assertEquals(fixture().path("session").path("request").path("installationId").asText(),
                value.installation().installationId());
        assertEquals("ACTIVE", value.installation().status());
        assertFalse(mapper.writeValueAsString(value.installation()).contains(value.runtimeAuthorization()));
        assertEquals(Set.of("installationId", "tenantId", "clientId", "canonicalAgentId",
                "manifestVersion", "manifestSha256", "enrollmentSecret"),
                fields(fixture().path("enrollment").path("request")));
    }
    @Test void sessionRequestRoundTripsWithExactFieldSet() throws Exception {
        var node = fixture().path("session").path("request");
        var value = mapper.treeToValue(node, AgentRuntimeV1SessionRequest.class);
        assertWireEquals(node, mapper.valueToTree(value));
        assertEquals(Set.of("installationId", "tenantId", "clientId", "canonicalAgentId",
                "manifestVersion", "manifestSha256", "hostId", "runtimeInstanceId"), fields(node));
    }
    @Test void sessionCredentialsHaveIndependentResponseAndRedactedDiagnosticString() throws Exception {
        var node = fixture().path("session").path("response");
        var value = mapper.treeToValue(node, AgentRuntimeV1SessionResponse.class);
        assertWireEquals(node, mapper.valueToTree(value));
        assertFalse(value.toString().contains(value.sessionToken()));
        assertTrue(value.toString().contains("REDACTED"));
        for (var component : AgentRuntimeV1InstallationView.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase().matches(".*(token|authorization|secret|credential).*"));
            assertNotEquals(AgentRuntimeV1SessionResponse.class, component.getType());
        }
        assertEquals("CHANNEL_PENDING", value.status());
    }
    @Test void wsAndHttpAckUseIdenticalNativeProofAndNeverUrlSecrets() throws Exception {
        var f = fixture();
        assertEquals(f.path("websocket").path("headers"), f.path("ack").path("headers"));
        assertEquals("/ws/agent/channel", f.path("websocket").path("path").asText());
        assertFalse(f.path("websocket").path("headers").has("X-API-Key"));
        var request = mapper.treeToValue(f.path("ack").path("request"), AgentRuntimeV1AckRequest.class);
        assertWireEquals(f.path("ack").path("request"), mapper.valueToTree(request));
        assertNull(request.payloadReference());
        assertEquals("1970-01-01T01:00:01.000Z",request.expiresAt());
        assertEquals(7, request.sessionGeneration());
        assertEquals(Long.valueOf(11), request.deliveryVersion());
    }
    @Test void onlyD06AdvancedOrPriorIsACommandCommitReceipt() throws Exception {
        for (var name : new String[]{"response", "priorResponse"}) {
            var result = mapper.treeToValue(fixture().path("ack").path(name), AgentCommandAckResult.class);
            assertTrue(Set.of(AgentCommandAckResult.Kind.ADVANCED, AgentCommandAckResult.Kind.PRIOR).contains(result.kind()));
            assertEquals("STARTED", result.status());
            assertEquals(12, result.deliveryVersion());
        }
    }
    @Test void firstAckDoesNotInventAClientKnownDeliveryVersion() throws Exception {
        var node = fixture().path("ack").path("firstRequest");
        var request = mapper.treeToValue(node, AgentRuntimeV1AckRequest.class);
        assertNull(request.deliveryVersion());
        assertWireEquals(node, mapper.valueToTree(request));
    }
    @Test void numericWidthComparisonStillRejectsMissingExtraOrChangedCredentialAndNumericTypes() throws Exception {
        var expected=mapper.readTree("{\"sessionGeneration\":7,\"sessionToken\":\"REDACTED_SESSION_TOKEN\",\"deliveryVersion\":null}");
        var same=((tools.jackson.databind.node.ObjectNode)expected).deepCopy();same.put("sessionGeneration",7L);
        assertWireEquals(expected,same);
        for(String key:new String[]{"sessionToken","deliveryVersion"}) {
            var missing=same.deepCopy();missing.remove(key);assertThrows(AssertionError.class,()->assertWireEquals(expected,missing));
        }
        var extra=same.deepCopy();extra.put("unknown",true);assertThrows(AssertionError.class,()->assertWireEquals(expected,extra));
        var wrongSecret=same.deepCopy();wrongSecret.put("sessionToken","DIFFERENT_TOKEN");assertThrows(AssertionError.class,()->assertWireEquals(expected,wrongSecret));
        for(String number:new String[]{"8","\"7\"","7.0","7.5","true","null"}) {
            var changed=same.deepCopy();changed.set("sessionGeneration",mapper.readTree(number));
            assertThrows(AssertionError.class,()->assertWireEquals(expected,changed));
        }
    }
    /** Compare every wire field and JSON kind; integer width (IntNode/LongNode) is not wire
     * semantics. Never coerce strings, null/missing fields, fractional values or credentials. */
    private static void assertWireEquals(JsonNode expected,JsonNode actual) {
        assertWireEquals(expected,actual,"$");
    }
    private static void assertWireEquals(JsonNode expected,JsonNode actual,String path) {
        assertNotNull(actual,path);
        if(expected.isObject()) {
            assertTrue(actual.isObject(),path+": object required");
            var expectedFields=new java.util.HashSet<String>();expected.propertyNames().forEach(expectedFields::add);
            var actualFields=new java.util.HashSet<String>();actual.propertyNames().forEach(actualFields::add);
            assertEquals(expectedFields,actualFields,path+": exact field set");
            for(String field:expectedFields) assertWireEquals(expected.get(field),actual.get(field),path+"/"+field);
        } else if(expected.isArray()) {
            assertTrue(actual.isArray(),path+": array required");assertEquals(expected.size(),actual.size(),path);
            for(int i=0;i<expected.size();i++) assertWireEquals(expected.get(i),actual.get(i),path+"/"+i);
        } else if(expected.isIntegralNumber()) {
            assertTrue(actual.isIntegralNumber(),path+": integer required");
            assertEquals(expected.bigIntegerValue(),actual.bigIntegerValue(),path+": exact integer value");
        } else {
            assertEquals(expected,actual,path);
        }
    }
    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>(); node.propertyNames().forEach(fields::add); return fields;
    }
}
