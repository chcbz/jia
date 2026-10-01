package cn.jia.chat.archive.maintenance.dto;

public record ArchiveResumeRequest(String reason, String expectedAppointmentId,
        String expectedAppointmentRevision, ArchiveSkillRef expectedSkill) { }
