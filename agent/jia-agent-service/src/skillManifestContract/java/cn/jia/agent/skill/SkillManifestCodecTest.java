package cn.jia.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        try (var stream = getClass().getResourceAsStream("/skill-manifest-contract-v1.json")) { return new ObjectMapper().readTree(stream); }
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
        var schema = new ObjectMapper().readTree("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"integer\"}");
        assertEquals(com.networknt.schema.Schema.class, codec.compileSchema(schema).getClass());
        assertEquals(1, codec.validateParameters(schema, "1".getBytes(StandardCharsets.UTF_8)).asInt());
        assertThrows(SkillMarketplaceException.class, () -> codec.validateParameters(schema, "\"1\"".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void instructionOnly() { assertNull(codec.parse(null, "notes", "1.0.0")); }
    @Test void costAndOriginalHash() throws Exception {
        for (var c : fixtures().get("cases")) if (c.path("id").asText().equals("bad-cost-only")) {
            var bytes = c.path("json").asText().getBytes(StandardCharsets.UTF_8); var m = codec.parse(bytes, "image-edit", "1.0.0");
            assertEquals("UNKNOWN", m.document().at("/costInfo/mode").asText());
            assertEquals("SKILL_COST_INFO_INVALID", m.document().at("/metadataWarnings/0").asText());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), m.manifestSha256());
            ((com.fasterxml.jackson.databind.node.ObjectNode)m.document()).put("skillKey", "changed");
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
