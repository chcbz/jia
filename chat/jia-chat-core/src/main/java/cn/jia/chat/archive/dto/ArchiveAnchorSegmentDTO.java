package cn.jia.chat.archive.dto;

public record ArchiveAnchorSegmentDTO(
        String paragraphId, long startByte, long endByte, String paragraphSha256) { }
