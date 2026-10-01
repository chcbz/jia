package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveRecoveryContextDTO(String jobId, String jobRevision,
        PreviousAppointment previousAppointment, List<CandidateAppointment> candidates) {
    public ArchiveRecoveryContextDTO { candidates = List.copyOf(candidates); }

    public record PreviousAppointment(String appointmentId, String revision,
            ArchiveSkillRef requiredSkill, String status) { }

    public record CandidateAppointment(String appointmentId, String revision,
            ArchiveSkillRef requiredSkill, String status, String agentId) { }
}
