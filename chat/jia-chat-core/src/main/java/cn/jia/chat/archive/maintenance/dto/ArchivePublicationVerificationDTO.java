package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchivePublicationVerificationDTO(String state, String revision,
        String verificationDigest, List<String> findings, String checkedAt) {
    public ArchivePublicationVerificationDTO { findings = List.copyOf(findings); }
}
