package cn.jia.chat.archive.maintenance.dto;

public record ArchiveWorkSummaryDTO(String workId, String title, String activeEditionId,
        String workRevision, boolean hasEditionHistory, String pendingJobId) {
    public ArchiveWorkSummaryDTO(String workId, String title, String activeEditionId) {
        this(workId, title, activeEditionId, null, activeEditionId != null, null);
    }
}
