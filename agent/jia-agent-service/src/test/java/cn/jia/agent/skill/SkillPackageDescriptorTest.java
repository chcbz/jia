package cn.jia.agent.skill;

import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillPackage;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SkillPackageDescriptorTest {
    private final SkillManifestCodec codec = new SkillManifestCodec();
    private PlatformSkillPackage packageOf(byte[] bytes) {
        return new PlatformSkillPackage(new PlatformSkillPackageCatalog().products().getFirst(), bytes);
    }
    @Test void parsesSmallManifestWithoutOverflowingTheStorageRepresentationLimit() throws Exception {
        byte[] manifest = """
            {"schemaVersion":1,"skillKey":"repo-inspector","skillVersion":"1.0.0","title":"Read","description":"inspect",
             "runner":"native-model","entrypoint":"repo.inspect",
             "parametersSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object"},
             "inputs":{"minItems":0,"maxItems":0,"acceptedMimeTypes":[]},
             "outputs":{"dataSchema":null,"mimeTypes":[],"multiple":false,"artifactsRequired":false},"requirements":{}}
            """.getBytes(StandardCharsets.UTF_8);
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("SKILL.md")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry();
            for (int i=0;i<300;i++) { zip.putNextEntry(new ZipEntry("assets/"+i+".txt")); zip.closeEntry(); }
        }
        var result = SkillPackageDescriptor.read(packageOf(buffer.toByteArray()), codec);
        assertEquals("repo-inspector", result.document().path("skillKey").asText());
        assertEquals(java.util.HexFormat.of().formatHex(SkillPackageStore.sha256(manifest)), result.manifestSha256());
    }
    @Test void retainsTotalExpansionProtectionForCompressedContent() throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("SKILL.md")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("large.bin"));
            byte[] chunk = new byte[1024*1024];
            for (int i=0;i<65;i++) zip.write(chunk);
            zip.closeEntry();
        }
        var error = assertThrows(SkillMarketplaceException.class,
                () -> SkillPackageDescriptor.read(packageOf(buffer.toByteArray()), codec));
        assertEquals("SKILL_ARCHIVE_INVALID", error.code());
    }
}
