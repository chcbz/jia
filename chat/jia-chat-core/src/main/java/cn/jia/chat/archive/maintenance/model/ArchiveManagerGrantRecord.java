package cn.jia.chat.archive.maintenance.model;

public record ArchiveManagerGrantRecord(String collectionId, String tenantId, String clientId,
        String ownerJiacn, String permissions, long revision, String state) {
    public boolean allows(String permission) {
        return "ACTIVE".equals(state) && java.util.Arrays.stream(permissions.split(","))
                .map(String::strip).anyMatch(permission::equals);
    }
}
