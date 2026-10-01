package cn.jia.chat.archive.maintenance.dto;

public record ArchiveInstalledSkillProofDTO(String installationRef, String revision,
        String key, String version, String packageSha256) { }
