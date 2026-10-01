package cn.jia.chat.archive.maintenance.dto;

public record ArchiveSkillReadinessDTO(String state, ArchiveInstalledSkillProofDTO proof,
        boolean executable, String blocker) { }
