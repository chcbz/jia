package cn.jia.chat.archive.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveTextCompilerTest {
    @Test void exactlyMatchesPinnedNativeSkillFixtureIncludingUnicodeAndCanonicalHashes() throws Exception {
        var json=new ObjectMapper();var source=json.readTree(new ClassPathResource("archive-native/source.json").getInputStream());
        var expected=json.readTree(new ClassPathResource("archive-native/result.json").getInputStream());
        var result=new ArchiveTextCompiler().compile(source.path("workId").asText(),source.path("editionId").asText(),source.path("title").asText(),source.path("sourceName").asText(),
                source.path("sourceText").asText().getBytes(StandardCharsets.UTF_8),source.path("sourceSha256").asText());
        assertEquals(expected.path("manifestJson").asText(),result.manifestJson());assertEquals(expected.path("manifestFileSha256").asText(),result.manifestFileSha256());
        assertEquals(2,result.manifest().chapterCount());assertEquals(3,result.manifest().readerParagraphCount());
    }
    @Test void refusesWrongDigestMalformedUtf8AndEmptyChapters() {
        for(byte[] bytes:new byte[][]{new byte[]{(byte)0xff},"第一回 空章\n第二回 有文\n内容".getBytes(StandardCharsets.UTF_8),new byte[0]})
            assertThrows(ArchiveContentException.class,()->new ArchiveTextCompiler().compile("book","edition","作品","source",bytes,ArchiveEtags.sha256(bytes)));
        assertThrows(ArchiveContentException.class,()->new ArchiveTextCompiler().compile("book","edition","作品","source","正文".getBytes(StandardCharsets.UTF_8),"a".repeat(64)));
    }
    @Test void derivesCountsFromSourceWithoutAssumingSpecificNovelOrChapterCount() {
        byte[] text="无标题的正文\n第二段。".getBytes(StandardCharsets.UTF_8);
        var result=new ArchiveTextCompiler().compile("book","edition","作品","source",text,ArchiveEtags.sha256(text));
        assertEquals(1,result.manifest().chapterCount());assertEquals(2,result.manifest().readerParagraphCount());
    }
}
