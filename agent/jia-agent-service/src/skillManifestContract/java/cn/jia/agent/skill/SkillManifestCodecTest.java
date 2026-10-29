package cn.jia.agent.skill;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import static org.junit.jupiter.api.Assertions.*;

class SkillManifestCodecTest {
    private final SkillManifestCodec codec = new SkillManifestCodec();
    private JsonNode fixtures() throws Exception {
        try (var stream = getClass().getResourceAsStream("/skill-manifest-contract-v1.json")) { return JsonMapper.builderWithJackson2Defaults().build().readTree(stream); }
    }
    @TestFactory Stream<DynamicTest> sharedContract() throws Exception {
        return StreamSupport.stream(fixtures().get("cases").spliterator(), false).map(c -> DynamicTest.dynamicTest(c.path("id").asText(), () -> {
            org.junit.jupiter.api.function.Executable action = () -> {
                var bytes = c.path("json").asText().getBytes(StandardCharsets.UTF_8);
                if (c.path("kind").asText().equals("manifest")) codec.parse(bytes, c.path("skillKey").asText(), c.path("skillVersion").asText());
                else codec.validateParameters(c.get("schema"), bytes);
            };
            if (c.path("expected").asBoolean()) assertDoesNotThrow(action); else assertThrows(SkillMarketplaceException.class, action);
        }));
    }
    @Test void usesProductionSchemaGenerationAndKeepsStrictTypes() throws Exception {
        var schema = JsonMapper.builderWithJackson2Defaults().build().readTree("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"integer\"}");
        assertEquals(com.networknt.schema.Schema.class, codec.compileSchema(schema).getClass());
        assertEquals(1, codec.validateParameters(schema, "1".getBytes(StandardCharsets.UTF_8)).asInt());
        assertThrows(SkillMarketplaceException.class, () -> codec.validateParameters(schema, "\"1\"".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void pdfSkillUsesTheSameAgentManifestWithoutApiPdfParsing() throws Exception {
        try (var input = getClass().getResourceAsStream("/pdf-extract-manifest-v1.json")) {
            byte[] bytes = input.readAllBytes();
            var manifest = codec.parse(bytes, "pdf-extract", "1.0.0");
            assertEquals("495e9f0ca235375ef531084803bffbe7bdf30440fe55297726a6781b8e35c127",
                    manifest.manifestSha256());
            assertEquals("local-script", manifest.document().path("runner").asText());
            assertEquals("application/pdf", manifest.document().at("/inputs/acceptedMimeTypes/0").asText());
            assertFalse(manifest.document().at("/outputs/artifactsRequired").asBoolean());
        }
    }
    @Test void jackson3ParsingRetainsStrictBoundaryErrors() {
        for (String raw : java.util.List.of("{", "{\"a\":1,\"a\":2}", "{} {}", "{\"a\":\"\\ud800\"}")) {
            var error = assertThrows(SkillMarketplaceException.class,
                    () -> codec.parseJson(raw.getBytes(StandardCharsets.UTF_8)), raw);
            assertEquals("SKILL_INVALID_JSON", error.code());
        }
        var value = codec.parseJson("{\"amount\":0.10000000000000001}".getBytes(StandardCharsets.UTF_8));
        assertEquals(new java.math.BigDecimal("0.10000000000000001"), value.path("amount").decimalValue());
    }
    @Test void instructionOnly() { assertNull(codec.parse(null, "notes", "1.0.0")); }
    @Test void costAndOriginalHash() throws Exception {
        for (var c : fixtures().get("cases")) if (c.path("id").asText().equals("bad-cost-only")) {
            var bytes = c.path("json").asText().getBytes(StandardCharsets.UTF_8); var m = codec.parse(bytes, "image-edit", "1.0.0");
            assertEquals("UNKNOWN", m.document().at("/costInfo/mode").asText());
            assertEquals("SKILL_COST_INFO_INVALID", m.document().at("/metadataWarnings/0").asText());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), m.manifestSha256());
            ((tools.jackson.databind.node.ObjectNode)m.document()).put("skillKey", "changed");
            assertEquals("image-edit", m.document().path("skillKey").asText());
        }
    }
    @Test void symlinkCannotEscape(@TempDir Path root) throws Exception {
        var pkg = Files.createDirectory(root.resolve("package")); var outside = Files.writeString(root.resolve("outside.py"), "secret");
        Files.createSymbolicLink(pkg.resolve("script.py"), outside);
        assertThrows(SkillMarketplaceException.class, () -> codec.resolveEntrypoint(pkg, "script.py"));
        Files.createSymbolicLink(pkg.resolve("scripts"), root);
        assertThrows(SkillMarketplaceException.class, () -> codec.resolveEntrypoint(pkg, "scripts/outside.py"));
        var good = Files.writeString(pkg.resolve("ok.py"), "print(1)"); assertEquals(good, codec.resolveEntrypoint(pkg, "ok.py"));
    }
}
