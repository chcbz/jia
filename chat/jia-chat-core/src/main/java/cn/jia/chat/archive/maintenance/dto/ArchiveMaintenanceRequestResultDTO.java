package cn.jia.chat.archive.maintenance.dto;

public record ArchiveMaintenanceRequestResultDTO(
        ArchiveJobDTO job,
        ArchiveExecutionDTO execution,
        String readiness,
        String nextAction,
        String confirmationRef) {
    public ArchiveMaintenanceRequestResultDTO(ArchiveJobDTO job, ArchiveExecutionDTO execution,
            String readiness, String nextAction) {
        this(job, execution, readiness, nextAction, null);
    }
}
