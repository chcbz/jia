package cn.jia.chat.archive.model;

/** Public immutable-edition metadata. Text is never updated in place. */
public record ArchiveEditionRecord(
        String editionId,
        String workId,
        String importState,
        String sourceSha256,
        String manifestSha256,
        String manifestFileSha256,
        long sourceUtf8ByteLength,
        int chapterCount,
        int prefaceParagraphCount,
        int chapterParagraphCount,
        int readerParagraphCount,
        long prefaceUtf8ByteLength,
        long chapterUtf8ByteLength,
        long readerUtf8ByteLength) {
    public ArchiveEditionRecord withImportState(String state) {
        return new ArchiveEditionRecord(editionId, workId, state, sourceSha256, manifestSha256,
                manifestFileSha256, sourceUtf8ByteLength, chapterCount, prefaceParagraphCount,
                chapterParagraphCount, readerParagraphCount, prefaceUtf8ByteLength,
                chapterUtf8ByteLength, readerUtf8ByteLength);
    }
}
