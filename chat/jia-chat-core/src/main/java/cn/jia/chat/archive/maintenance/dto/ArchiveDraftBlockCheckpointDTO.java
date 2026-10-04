package cn.jia.chat.archive.maintenance.dto;

public record ArchiveDraftBlockCheckpointDTO(String blockKey, String draftRevision,
        String digest, String byteLength) { }
