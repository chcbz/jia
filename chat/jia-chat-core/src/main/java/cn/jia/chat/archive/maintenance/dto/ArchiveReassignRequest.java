package cn.jia.chat.archive.maintenance.dto;

public record ArchiveReassignRequest(String reason, String expectedAppointmentId,
        String expectedAppointmentRevision, ArchiveSkillRef expectedSkill,
        String newAppointmentId, String newAppointmentRevision, ArchiveSkillRef newSkill) { }
