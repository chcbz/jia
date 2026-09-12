package cn.jia.chat.archive.model;

/** Ordered preface/chapter content block. */
public record ArchiveBlockRecord(
        String editionId,
        String blockId,
        String blockType,
        int readerOrdinal,
        Integer chapterNumber,
        String title,
        int paragraphCount,
        long utf8ByteLength,
        String blockContentSha256) {
}
