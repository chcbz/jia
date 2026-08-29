package cn.jia.chat.archive.dto;

public record ArchiveAnchorSegmentDTO(
        String paragraphId, Long startByte, Long endByte, String paragraphSha256) { }
