package cn.jia.agent.platform;

import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PlatformSkillCatalogTest {
    @Test void fixedApprovedResourcesProduceStableStoredZipAndExternalDigestBinding() throws Exception {
        PlatformSkillCatalog first = new PlatformSkillCatalog();
        PlatformSkillCatalog second = new PlatformSkillCatalog();
        byte[] bytes = first.packageBytes(PlatformSkillCatalog.SKILL_KEY, PlatformSkillCatalog.SKILL_VERSION, first.sha256());
        assertArrayEquals(bytes, second.packageBytes(PlatformSkillCatalog.SKILL_KEY, PlatformSkillCatalog.SKILL_VERSION, second.sha256()));
        assertEquals(PlatformSkillCatalog.APPROVED_RELEASE_SHA256, first.sha256());
        assertEquals(PlatformSkillCatalog.APPROVED_RELEASE_SHA256, HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(bytes)));
        List<String> names = new ArrayList<>();
        String manifestJson = null;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
                assertEquals(0L, entry.getTime());
                if ("manifest.json".equals(entry.getName())) manifestJson = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        assertEquals(List.of("manifest.json", "SKILL.md", "schemas/content.json", "scripts/parse-text.mjs", "scripts/check-content.mjs",
                "fixtures/ordinary-crlf-bom-emoji.txt", "fixtures/ordinary-crlf-bom-emoji.expected.json", "fixtures/no-preface-multichapter.txt",
                "fixtures/no-preface-multichapter.expected.json", "fixtures/malicious-instructions.txt", "fixtures/malicious-instructions.expected.json",
                "fixtures/invalid-leading-body.txt", "fixtures/invalid-empty-chapter.txt", "fixtures/invalid-number-gap.txt",
                "fixtures/invalid-ambiguous-numeral.txt", "fixtures/invalid-heading-edge-formatting.txt",
                "fixtures/invalid-tampered-schema.json", "fixtures/invalid-tampered-range.json", "fixtures/parse-text.test.mjs"), names);
        var manifest = JsonMapper.builder().build().readTree(manifestJson);
        assertEquals(PlatformSkillCatalog.SKILL_KEY, manifest.path("key").asText());
        assertEquals(PlatformSkillCatalog.SKILL_VERSION, manifest.path("version").asText());
        assertEquals(PlatformSkillCatalog.PROTOCOL, manifest.path("protocol").asText());
        assertEquals("schemas/content.json", manifest.path("contentSchema").asText());
        assertFalse(manifest.has("packageSha256"));
        List<String> declared = new ArrayList<>();
        manifest.path("entries").forEach(node -> declared.add(node.asText()));
        assertEquals(names.subList(1, names.size()), declared);
    }
}