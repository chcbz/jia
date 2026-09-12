package cn.jia.chat.archive.dto;

import java.util.List;

public record ArchiveTextAnchorDTO(
        String editionManifestSha256, String blockType, String blockId,
        List<ArchiveAnchorSegmentDTO> segments, String selectionSha256) { }
