package cn.jia.agent.platform;

import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The only platform-provisioned release package. Its SHA-256 binds an installation externally;
 * no ZIP entry declares its own package digest, so a later release cannot rewrite an in-flight one.
 */
@Component
@ConditionalOnProperty(prefix = "agent.platform-skills", name = "enabled", havingValue = "true")
public final class PlatformSkillCatalog {
    public static final String SKILL_KEY = "archive-maintainer";
    public static final String SKILL_VERSION = "1.0.0";
    public static final String PROTOCOL = "archive-maintainer/utf8-exact-v1";
    /** Frozen external release digest; it is deliberately absent from the ZIP manifest. */
    public static final String APPROVED_RELEASE_SHA256 = "8894d96341067dd7f9e2f45696eef44057dc61346255a0323b2d713a3c7ea081";
    private static final String ROOT = "/platform-skills/archive-maintainer/";
    private static final List<String> PAYLOAD_ENTRIES = List.of(
            "SKILL.md",
            "schemas/content.json",
            "scripts/parse-text.mjs",
            "scripts/check-content.mjs",
            "fixtures/ordinary-crlf-bom-emoji.txt",
            "fixtures/ordinary-crlf-bom-emoji.expected.json",
            "fixtures/no-preface-multichapter.txt",
            "fixtures/no-preface-multichapter.expected.json",
            "fixtures/malicious-instructions.txt",
            "fixtures/malicious-instructions.expected.json",
            "fixtures/invalid-leading-body.txt",
            "fixtures/invalid-empty-chapter.txt",
            "fixtures/invalid-number-gap.txt",
            "fixtures/invalid-ambiguous-numeral.txt",
            "fixtures/invalid-heading-edge-formatting.txt",
            "fixtures/invalid-tampered-schema.json",
            "fixtures/invalid-tampered-range.json",
            "fixtures/parse-text.test.mjs");
    private final byte[] bytes;
    private final String sha256;

    public PlatformSkillCatalog() {
        try {
            byte[] manifest = resource("manifest.json");
            validateManifest(manifest);
            try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                put(zip, "manifest.json", manifest);
                for (String entry : PAYLOAD_ENTRIES) put(zip, entry, resource(entry));
                zip.finish();
                bytes = output.toByteArray();
                sha256 = HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(bytes));
                if (!APPROVED_RELEASE_SHA256.equals(sha256))
                    throw new IllegalStateException("Approved platform skill bytes changed; release a new version and installation");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Approved platform skill unavailable", failure);
        }
    }

    public byte[] packageBytes(String key, String version, String expectedSha) {
        PlatformSkillException.require(isApproved(key, version, expectedSha), 409, "PLATFORM_SKILL_PACKAGE_REVOKED");
        return bytes.clone();
    }

    public boolean isApproved(String key, String version, String expectedSha) {
        return SKILL_KEY.equals(key) && SKILL_VERSION.equals(version) && sha256.equals(expectedSha);
    }

    public String sha256() { return sha256; }

    private static byte[] resource(String entry) throws IOException {
        try (InputStream input = PlatformSkillCatalog.class.getResourceAsStream(ROOT + entry)) {
            if (input == null) throw new IOException("Approved platform skill resource missing: " + entry);
            return input.readAllBytes();
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static void validateManifest(byte[] bytes) throws IOException {
        JsonNode manifest = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);
        if (manifest == null || !manifest.isObject()
                || !SKILL_KEY.equals(manifest.path("key").asText())
                || !SKILL_VERSION.equals(manifest.path("version").asText())
                || !PROTOCOL.equals(manifest.path("protocol").asText())
                || !"plain-text-v1".equals(manifest.path("adapter").asText())
                || !"schemas/content.json".equals(manifest.path("contentSchema").asText())
                || !"external-installation-receipt-sha256".equals(manifest.path("packageDigestBinding").asText())
                || manifest.has("packageSha256")
                || !manifest.path("entries").isArray()) {
            throw new IOException("Approved platform skill manifest is inconsistent");
        }
        List<String> declared = new java.util.ArrayList<>();
        manifest.path("entries").forEach(node -> {
            if (!node.isTextual()) throw new IllegalArgumentException("manifest entries must be strings");
            declared.add(node.textValue());
        });
        if (!PAYLOAD_ENTRIES.equals(declared)) throw new IOException("Approved platform skill manifest entries are inconsistent");
    }
}