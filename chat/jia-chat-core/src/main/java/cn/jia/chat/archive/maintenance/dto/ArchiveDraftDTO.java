package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveDraftDTO(String draftId, String jobId, String revision, String state,
        ArchiveDraftUpdateRequest content, String contentSha256, String validatedRevision,
        String validationId, List<ArchiveDraftBlockCheckpointDTO> checkpoints) {
    public ArchiveDraftDTO {
        checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }

    public ArchiveDraftDTO(String draftId, String jobId, String revision, String state,
            ArchiveDraftUpdateRequest content, String contentSha256, String validatedRevision,
            String validationId) {
        this(draftId, jobId, revision, state, content, contentSha256, validatedRevision,
                validationId, List.of());
    }
}
