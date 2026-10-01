package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveAppointmentCreateRequest(String agentId, String expectedBindingVersion,
        String workScopeMode, List<String> workIds, String permissionProfile, ArchiveSkillRef requiredSkill) {
    public ArchiveAppointmentCreateRequest { workIds = workIds == null ? List.of() : List.copyOf(workIds); }
}
