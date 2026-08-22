package cn.jia.chat.archive.dto;

public record ArchivePointLocationDTO(
        String editionManifestSha256, String blockType, String blockId, String paragraphId,
        long byteOffset, String paragraphSha256) { }
