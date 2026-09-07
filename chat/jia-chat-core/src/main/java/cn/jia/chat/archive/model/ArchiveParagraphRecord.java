package cn.jia.chat.archive.model;

/** Byte-addressable immutable paragraph. */
public record ArchiveParagraphRecord(
        String editionId,
        String blockId,
        String paragraphId,
        int ordinal,
        String text,
        long utf8ByteLength,
        String sha256) {
}
