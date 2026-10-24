package cn.jia.agent.skill;

import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillPackage;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog.PlatformSkillProduct;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillPackageDescriptorTest {
    private final SkillManifestCodec codec=new SkillManifestCodec();
    private byte[] fixture(String id) throws Exception {
        try(var stream=getClass().getResourceAsStream("/skill-manifest-contract-v1.json")) {
            for(var c:new ObjectMapper().readTree(stream).path("cases"))
                if(id.equals(c.path("id").asText())) return c.path("json").asText().getBytes(StandardCharsets.UTF_8);
        }
        throw new AssertionError("fixture missing");
    }
    private PlatformSkillPackage archive(Map<String,byte[]> files) throws Exception {
        var buffer=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(buffer)) {
            for(var e:files.entrySet()) { zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry(); }
        }
        byte[] bytes=buffer.toByteArray();return new PlatformSkillPackage(new PlatformSkillProduct("sp","spv","image-edit","1.0.0",
                "image","",0L,"NONE",null,SkillPackageStore.sha256(bytes),bytes.length,"[]",SkillPackageStore.sha256("[]".getBytes())),bytes);
    }
    @Test void ordinaryExplanationHasNoExecutionDescriptorOrDigest() throws Exception {
        var p=archive(Map.of("SKILL.md","instructions".getBytes()));assertNull(SkillPackageDescriptor.read(p,codec));
        assertDoesNotThrow(()->SkillInstallResultService.validateManifestBinding(Map.of(),null));
        assertThrows(SkillMarketplaceException.class,()->SkillInstallResultService.validateManifestBinding(Map.of("manifestSha256","0".repeat(64)),null));
    }
    @Test void executableResultMustMatchOriginalManifestBytes() throws Exception {
        byte[] bytes=fixture("image-edit");var p=archive(Map.of("SKILL.md","instructions".getBytes(),"manifest.json",bytes));
        var descriptor=SkillPackageDescriptor.read(p,codec);
        assertEquals(HexFormat.of().formatHex(SkillPackageStore.sha256(bytes)),descriptor.manifestSha256());
        assertDoesNotThrow(()->SkillInstallResultService.validateManifestBinding(Map.of("manifestSha256",descriptor.manifestSha256()),descriptor));
        assertThrows(SkillMarketplaceException.class,()->SkillInstallResultService.validateManifestBinding(Map.of(),descriptor));
        assertThrows(SkillMarketplaceException.class,()->SkillInstallResultService.validateManifestBinding(Map.of("manifestSha256","0".repeat(64)),descriptor));
    }
    @Test void invalidCostStillHasBoundOriginalDigest() throws Exception {
        byte[] bytes=fixture("bad-cost-only");var descriptor=SkillPackageDescriptor.read(archive(Map.of("SKILL.md","instructions".getBytes(),"manifest.json",bytes)),codec);
        assertEquals("UNKNOWN",descriptor.document().at("/costInfo/mode").asText());
        assertEquals(HexFormat.of().formatHex(SkillPackageStore.sha256(bytes)),descriptor.manifestSha256());
    }
    @Test void invalidManifestAndIdentityDoNotBecomeExecutionDescriptors() throws Exception {
        for(String id:List.of("unknown-field","wrong-identity")) {
            var p=archive(Map.of("SKILL.md","instructions".getBytes(),"manifest.json",fixture(id)));
            assertThrows(SkillMarketplaceException.class,()->SkillPackageDescriptor.read(p,codec));
        }
    }
    @Test void absentInstructionsOrDirectoryManifestRejected() throws Exception {
        var absent=archive(Map.of("manifest.json",fixture("image-edit")));
        assertThrows(SkillMarketplaceException.class,()->SkillPackageDescriptor.read(absent,codec));
        var directory=archive(Map.of("SKILL.md","instructions".getBytes(),"manifest.json/",new byte[0]));
        // A directory named manifest.json cannot be silently classified as ordinary instructions.
        assertThrows(SkillMarketplaceException.class,()->SkillPackageDescriptor.read(directory,codec));
    }
    @Test void localScriptRequiresPackageEntry() throws Exception {
        String raw=new String(fixture("image-edit"),StandardCharsets.UTF_8)
                .replace("provider-operation","local-script").replace("image-http.edit","scripts/export.py");
        byte[] manifest=raw.getBytes(StandardCharsets.UTF_8);
        var missing=archive(Map.of("SKILL.md","instructions".getBytes(),"manifest.json",manifest));
        assertThrows(SkillMarketplaceException.class,()->SkillPackageDescriptor.read(missing,codec));
        var present=archive(Map.of("SKILL.md","instructions".getBytes(),"manifest.json",manifest,"scripts/export.py","print(1)".getBytes()));
        assertEquals("local-script",SkillPackageDescriptor.read(present,codec).document().path("runner").asText());
    }
    @Test void ordinaryArchiveIsNotRejectedForEntryCount() throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();files.put("SKILL.md","instructions".getBytes());
        for(int i=0;i<256;i++) files.put("references/"+i,new byte[0]);var p=archive(files);
        assertNull(SkillPackageDescriptor.read(p,codec));
    }
}
