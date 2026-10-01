package cn.jia.chat.archive.maintenance.dto;

public record ArchiveResolveInputRequest(
        String sourceId,
        ArchiveNewWorkRequest newWork,
        String workId,
        String expectedAppointmentId,
        String expectedAppointmentRevision,
        ArchiveSkillRef expectedSkill) { }