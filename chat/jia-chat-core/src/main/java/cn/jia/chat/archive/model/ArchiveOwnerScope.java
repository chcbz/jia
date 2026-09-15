package cn.jia.chat.archive.model;

/** Exact server-derived personal-data scope in the single tenant. */
public record ArchiveOwnerScope(String tenantId, String clientId, String ownerJiacn) {
    public static final String SINGLE_TENANT = "0";

    /**
     * Tenant is not caller-controlled: all archive personal-data paths run in the sole tenant.
     * Client and owner remain separate exact authorization keys.
     */
    public ArchiveOwnerScope {
        tenantId = SINGLE_TENANT;
    }
}
