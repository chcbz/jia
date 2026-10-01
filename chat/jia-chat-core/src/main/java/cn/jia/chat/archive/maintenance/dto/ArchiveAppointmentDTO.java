package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveAppointmentDTO(String appointmentId, String collectionId, String roleCode,
        String agentId, String bindingVersion, String workScopeMode, List<String> workIds,
        String permissionProfile, ArchiveSkillRef requiredSkill, String status, String revision,
        String readiness, ArchiveSkillReadinessDTO skillReadiness) {
    public ArchiveAppointmentDTO { workIds = List.copyOf(workIds); }
}
