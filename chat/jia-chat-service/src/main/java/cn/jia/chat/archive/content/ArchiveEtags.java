package cn.jia.chat.archive.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ArchiveEtags {
    public static final int REPRESENTATION_SCHEMA_VERSION = 1;

    private ArchiveEtags() {
    }

    public static String catalog(String manifestSha256) {
        return "W/\"archive-catalog-json-v1-" + manifestSha256 + "\"";
    }

    public static String block(String manifestSha256, ArchiveManifest.Block block) {
        return blockFromDigest(blockContentSha256(manifestSha256, block));
    }

    public static String blockFromDigest(String digest) {
        return "W/\"archive-block-json-v1-" + digest + "\"";
    }

    public static String blockContentSha256(String manifestSha256, ArchiveManifest.Block block) {
        StringBuilder canonical = new StringBuilder()
                .append(manifestSha256).append('\n')
                .append(block.blockType()).append('\n')
                .append(block.blockId()).append('\n')
                .append(block.title()).append('\n');
        for (ArchiveManifest.Paragraph paragraph : block.paragraphs()) {
            canonical.append(paragraph.paragraphId()).append('\t')
                    .append(paragraph.sha256()).append('\t')
                    .append(paragraph.utf8ByteLength()).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
