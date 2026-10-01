package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveCapabilitiesDTO(String collectionId, List<String> allowedActions,
        String appointmentStatus, String readiness) {
    public ArchiveCapabilitiesDTO { allowedActions = List.copyOf(allowedActions); }
}
