package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatTypedInspectionContextContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void authorityFixtureMatchesManifestAuthorizationAndSourceRefAlgorithms() throws Exception {
        Map<String, Object> fixture;
        try (InputStream input = getClass().getResourceAsStream(
                "/contracts/typed-inspection-authority-v1.json")) {
            if (input == null) throw new IllegalStateException("fixture missing");
            fixture = JSON.readValue(input, Map.class);
        }
        Map<String, Object> manifest = (Map<String, Object>) fixture.get("manifest_example");
        Map<String, Object> scope = (Map<String, Object>) manifest.get("scope");
        Map<String, Object> sourceCatalog = (Map<String, Object>) fixture.get("source_catalog_fixture");
        ChatTypedInspectionContextService.Scope typedScope = new ChatTypedInspectionContextService.Scope(
                (String) scope.get("tenantId"), (String) scope.get("ownerJiacn"),
                (String) scope.get("clientId"), (String) scope.get("conversationId"),
                Long.parseLong((String) scope.get("conversationGeneration")),
                (String) scope.get("taskId"), Long.parseLong((String) scope.get("assignmentRevision")),
                (String) scope.get("requestId"), Long.parseLong((String) scope.get("requestRevision")),
                (String) scope.get("targetAgentId"));
        Map<String, Object> expectedSource = (Map<String, Object>) ((java.util.List<?>) manifest.get("sources")).getFirst();
        assertEquals(expectedSource.get("sourceRefId"),
                ChatTypedInspectionContextService.sourceRefId(typedScope, sourceCatalog));
        assertEquals(fixture.get("canonical_manifest"), CanonicalContextJson.write(manifest));
        assertEquals(fixture.get("manifest_digest"), ChatDeliberationService.digest(manifest));
        String snapshot = CanonicalContextJson.write(manifest);
        assertFalse(snapshot.contains("storage_uri"));
        assertFalse(snapshot.contains("http://"));
        assertFalse(snapshot.contains("https://"));
        assertFalse(snapshot.contains("/tmp/"));
    }
}
