package cn.jia.chat.archive.maintenance.dto;

import cn.jia.core.security.ExactContentOutput;

public record ArchiveWorkSummaryDTO(String workId,
        @ExactContentOutput(reason = "validated archive work title must be byte-faithful") String title,
        String activeEditionId, String workRevision, boolean hasEditionHistory, String pendingJobId) {
    public ArchiveWorkSummaryDTO(String workId, String title, String activeEditionId) {
        this(workId, title, activeEditionId, null, activeEditionId != null, null);
    }
}
