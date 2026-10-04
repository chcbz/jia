package cn.jia.chat.archive.maintenance.dto;

public record ArchiveDraftBlockDTO(String draftId, String jobId, String revision, String state,
        ArchiveDraftBlockInput block, ArchiveDraftBlockCheckpointDTO checkpoint) {
    public ArchiveDraftBlockDTO(String draftId, String jobId, String revision, String state,
            ArchiveDraftBlockInput block) {
        this(draftId, jobId, revision, state, block, null);
    }
}
