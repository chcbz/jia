package cn.jia.chat.archive.content;

public record ArchiveManifestBundle(
        ArchiveManifest manifest,
        ArchiveGoldenSummary goldenSummary,
        String manifestFileSha256,
        String goldenSummaryFileSha256) {
}
