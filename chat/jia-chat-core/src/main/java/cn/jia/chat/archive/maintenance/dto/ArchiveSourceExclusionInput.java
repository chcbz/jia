package cn.jia.chat.archive.maintenance.dto;

public record ArchiveSourceExclusionInput(Integer startByte, Integer endByte, String reason) { }
