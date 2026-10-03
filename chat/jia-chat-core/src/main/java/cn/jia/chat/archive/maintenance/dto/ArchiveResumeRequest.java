package cn.jia.chat.archive.maintenance.dto;

public record ArchiveResumeRequest(String reason, String expectedAppointmentId,
        String expectedAppointmentRevision, ArchiveSkillRef expectedSkill,
        ArchiveRepairResolution repairResolution) {
    public ArchiveResumeRequest(String reason, String expectedAppointmentId,
            String expectedAppointmentRevision, ArchiveSkillRef expectedSkill) {
        this(reason, expectedAppointmentId, expectedAppointmentRevision, expectedSkill, null);
    }
}
