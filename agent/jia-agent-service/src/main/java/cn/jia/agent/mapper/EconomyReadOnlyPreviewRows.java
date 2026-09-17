package cn.jia.agent.mapper;

import lombok.Data;
import lombok.experimental.Accessors;

/** Narrow projections for the v1.7 SELECT-only preview mapper. */
public final class EconomyReadOnlyPreviewRows {
    private EconomyReadOnlyPreviewRows() { }

    @Data
    @Accessors(chain = true)
    public static class ProductRow {
        private String productId;
        private String name;
        private String description;
        private String productVersionId;
        private String skillKey;
        private String skillVersion;
        private Long priceMicro;
        private String approvedPermissionsManifest;
        private String deploymentRestriction;
    }

    @Data
    @Accessors(chain = true)
    public static class AgentOwnershipRow {
        private Long bindingId;
        private String tenantId;
        private String clientId;
        private String ownerJiacn;
        private String agentId;
        private String personaCode;
    }
}
