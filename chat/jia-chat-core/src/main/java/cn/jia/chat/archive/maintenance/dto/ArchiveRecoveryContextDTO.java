package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveRecoveryContextDTO(String jobId, String jobRevision,
        PreviousAppointment previousAppointment, List<CandidateAppointment> candidates,
        boolean resumeAllowed, LatestFailure latestFailure) {
    public ArchiveRecoveryContextDTO {
        candidates = List.copyOf(candidates);
    }

    public ArchiveRecoveryContextDTO(String jobId, String jobRevision,
            PreviousAppointment previousAppointment, List<CandidateAppointment> candidates) {
        this(jobId, jobRevision, previousAppointment, candidates, true, null);
    }

    public record PreviousAppointment(String appointmentId, String revision,
            ArchiveSkillRef requiredSkill, String status) { }

    public record CandidateAppointment(String appointmentId, String revision,
            ArchiveSkillRef requiredSkill, String status, String agentId,
            boolean recoveryAllowed, boolean inputChanged) {
        public CandidateAppointment(String appointmentId, String revision,
                ArchiveSkillRef requiredSkill, String status, String agentId) {
            this(appointmentId, revision, requiredSkill, status, agentId, true, false);
        }
    }

    public record LatestFailure(String failureId, String phase, String code,
            boolean retryable, String diagnostic, boolean blockedRootCause,
            boolean inputChangedForResume, List<String> allowedRepairResolutionCodes) {
        public LatestFailure {
            allowedRepairResolutionCodes = List.copyOf(allowedRepairResolutionCodes);
        }
    }
}
