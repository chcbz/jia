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
    @Test void sessionRequestRoundTripsWithExactFieldSet() throws Exception {
        var node = fixture().path("session").path("request");
        var value = mapper.treeToValue(node, AgentRuntimeV1SessionRequest.class);
        assertEquals(node, mapper.valueToTree(value));
        assertEquals(Set.of("installationId", "tenantId", "clientId", "canonicalAgentId",
                "manifestVersion", "manifestSha256", "hostId", "runtimeInstanceId"), fields(node));
    }
    @Test void sessionCredentialsHaveIndependentResponseAndRedactedDiagnosticString() throws Exception {
        var node = fixture().path("session").path("response");
        var value = mapper.treeToValue(node, AgentRuntimeV1SessionResponse.class);
        assertEquals(node, mapper.valueToTree(value));
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
        assertEquals(f.path("ack").path("request"), mapper.valueToTree(request));
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
        assertEquals(node, mapper.valueToTree(request));
    }
    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>(); node.propertyNames().forEach(fields::add); return fields;
    }
}
