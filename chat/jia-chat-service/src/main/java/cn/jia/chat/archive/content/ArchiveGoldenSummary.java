package cn.jia.chat.archive.content;

public record ArchiveGoldenSummary(
        String catalogEtag,
        int chapterCount,
        int chapterParagraphCount,
        long chapterUtf8ByteLength,
        String editionId,
        ChapterSummary firstChapter,
        String firstChapterEtag,
        String firstParagraphId,
        ChapterSummary lastChapter,
        String lastChapterEtag,
        String lastParagraphId,
        String manifestSha256,
        String prefaceEtag,
        int prefaceParagraphCount,
        long prefaceUtf8ByteLength,
        int readerParagraphCount,
        long readerUtf8ByteLength,
        int representationSchemaVersion,
        String sourceSha256,
        String workTitle) {
    public record ChapterSummary(String chapterId, String title) {
    }
}
