package cn.jia.agent.skill;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class SkillPublicationToolArchiveTest {
    private final SkillPublicationToolArchive.File skill=new SkillPublicationToolArchive.File("SKILL.md","---\nname: example\ndescription: 示例\n---\n","utf8");
    @Test void sortedArchiveIsDeterministicAndPreservesTextAndBinary() throws Exception {
        var binary=new SkillPublicationToolArchive.File("assets/sample.bin","AP+A","base64");
        byte[] first=SkillPublicationToolArchive.encode(List.of(skill,binary));
        assertArrayEquals(first,SkillPublicationToolArchive.encode(List.of(binary,skill)));
        var content=new HashMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(first),StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null)content.put(entry.getName(),zip.readAllBytes());
        }
        assertEquals(2,content.size());assertArrayEquals(skill.content().getBytes(StandardCharsets.UTF_8),content.get("SKILL.md"));
        assertArrayEquals(new byte[]{0,(byte)255,(byte)128},content.get("assets/sample.bin"));
    }
    @Test void invalidPathsCannotEscapeOrAliasPackageEntries() {
        for(String path:List.of("../SKILL.md","/SKILL.md","a/../SKILL.md","a//b","a\\b","./SKILL.md","a/"))
            assertThrows(SkillMarketplaceException.class,()->SkillPublicationToolArchive.encode(List.of(skill,new SkillPublicationToolArchive.File(path,"x","utf8"))),path);
    }
    @Test void missingSkillDuplicatesAndInvalidEncodingAreRejected() {
        assertThrows(SkillMarketplaceException.class,()->SkillPublicationToolArchive.encode(List.of()));
        assertThrows(SkillMarketplaceException.class,()->SkillPublicationToolArchive.encode(List.of(skill,skill)));
        assertThrows(SkillMarketplaceException.class,()->SkillPublicationToolArchive.encode(List.of(new SkillPublicationToolArchive.File("other.md","x","utf8"))));
        for(String encoding:List.of("unknown","base64"))
            assertThrows(SkillMarketplaceException.class,()->SkillPublicationToolArchive.encode(List.of(skill,new SkillPublicationToolArchive.File("x","!",encoding))));
    }
}
