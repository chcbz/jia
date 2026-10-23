package cn.jia.agent.position;

import java.util.*;

/** Persisted domain state. Scope and credentials deliberately live outside this aggregate. */
public record PositionSkillState(int schemaVersion, Map<String, Skill> skills,
        Map<String, Position> positions, Map<String, Appointment> appointments,
        Map<String, Installation> installations) {
    public enum SkillType { POSITION, GENERAL }
    public enum InstallState { INSTALL_PENDING, INSTALLED, REMOVE_PENDING, REMOVED, FAILED }
    public record Skill(String key, SkillType type, String productVersionId) { }
    public record Position(String id, String title, int capacity, String resource,
            boolean allowPublish, List<String> skillKeys, long revision) {
        public Position { skillKeys = List.copyOf(skillKeys); }
    }
    public record Appointment(String id, String positionId, String agentId, long bindingId,
            boolean active, long revision) { }
    public record Installation(String id, String agentId, long bindingId, String skillKey,
            boolean retained, boolean desired, long revision, InstallState state,
            String receiptDigest, String packageDigest, String manifestSha256, String runtimeInstallationId, String runtimeHostId, String errorCode) {
        public boolean usable() { return desired && state == InstallState.INSTALLED && receiptDigest != null; }
    }
    public PositionSkillState {
        if (schemaVersion != 1) throw new IllegalArgumentException("POSITION_SCHEMA_UNSUPPORTED");
        skills = Map.copyOf(skills); positions = Map.copyOf(positions);
        appointments = Map.copyOf(appointments); installations = Map.copyOf(installations);
    }
    public static PositionSkillState empty() {
        return new PositionSkillState(1, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
