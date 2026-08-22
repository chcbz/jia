package cn.jia.chat.archive.content;

import java.util.ArrayList;
import java.util.List;

public record ArchiveManifest(
        int chapterCount,
        int chapterParagraphCount,
        long chapterUtf8ByteLength,
        List<Block> chapters,
        String editionId,
        String manifestSha256,
        Block preface,
        int prefaceParagraphCount,
        long prefaceUtf8ByteLength,
        int readerParagraphCount,
        long readerUtf8ByteLength,
        int schemaVersion,
        Source source,
        String title,
        String workId) {
    public ArchiveManifest {
        chapters = chapters == null ? null : List.copyOf(chapters);
    }

    public List<Block> blocksInReaderOrder() {
        List<Block> blocks = new ArrayList<>(chapterCount + 1);
        blocks.add(preface);
        blocks.addAll(chapters);
        return List.copyOf(blocks);
    }

    public record Source(
            List<String> excludedNoticeLines,
            String file,
            String page,
            String sha256,
            String upstreamCommit,
            String upstreamRepo,
            long utf8ByteLength) {
        public Source {
            excludedNoticeLines = excludedNoticeLines == null ? null : List.copyOf(excludedNoticeLines);
        }
    }

    public record Block(
            String chapterId,
            Integer number,
            int paragraphCount,
            List<Paragraph> paragraphs,
            String prefaceId,
            String title,
            long utf8ByteLength) {
        public Block {
            paragraphs = paragraphs == null ? null : List.copyOf(paragraphs);
        }

        public String blockId() {
            return prefaceId != null ? prefaceId : chapterId;
        }

        public String blockType() {
            return prefaceId != null ? "PREFACE" : "CHAPTER";
        }

        public int readerOrdinal() {
            return number == null ? 0 : number;
        }
    }

    public record Paragraph(String paragraphId, String sha256, String text, long utf8ByteLength) {
    }
}
