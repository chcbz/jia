package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveValidationDTO(String validationId, String draftId, String draftRevision,
        String outcome, String validationDigest, List<String> findings) {
    public ArchiveValidationDTO { findings = List.copyOf(findings); }
}
