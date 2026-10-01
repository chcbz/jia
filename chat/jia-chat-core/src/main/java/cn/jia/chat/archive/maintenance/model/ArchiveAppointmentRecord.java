package cn.jia.chat.archive.maintenance.model;

import java.time.Instant;

public record ArchiveAppointmentRecord(String appointmentId, String collectionId, String roleCode,
        String tenantId, String clientId, String ownerJiacn, String agentId, String bindingVersion,
        String workScopeMode, String workIds, String permissionProfile, String requiredSkillKey,
        String requiredSkillVersion, String requiredSkillSha256, String status, long revision,
        Instant createdAt, Instant revokedAt) { }
