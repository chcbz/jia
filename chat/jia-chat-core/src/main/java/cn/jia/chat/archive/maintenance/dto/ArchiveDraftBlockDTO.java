package cn.jia.chat.archive.maintenance.dto;

public record ArchiveDraftBlockDTO(String draftId, String jobId, String revision, String state,
        ArchiveDraftBlockInput block) { }
