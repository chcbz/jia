package cn.jia.chat.archive.maintenance.dto;

public record ArchiveReassignRequest(String reason, String expectedAppointmentId,
        String expectedAppointmentRevision, ArchiveSkillRef expectedSkill,
        String newAppointmentId, String newAppointmentRevision, ArchiveSkillRef newSkill,
        ArchiveRepairResolution repairResolution) {
    public ArchiveReassignRequest(String reason, String expectedAppointmentId,
            String expectedAppointmentRevision, ArchiveSkillRef expectedSkill,
            String newAppointmentId, String newAppointmentRevision, ArchiveSkillRef newSkill) {
        this(reason, expectedAppointmentId, expectedAppointmentRevision, expectedSkill,
                newAppointmentId, newAppointmentRevision, newSkill, null);
    }
}
