package cn.jia.agent.mapper;

import lombok.Data;

/** Per-request owner binding/identity/runtime overlay; this projection is never shared-cached. */
@Data
public class AgentPersonaCatalogBindingRow {
    private Long bindingId;
    private String bindingTenantId;
    private String bindingClientId;
    private String bindingOwnerJiacn;
    private String personaCode;
    private String bindingAgentId;
    private Integer bindingStatus;
    private Long identityId;
    private Long identityBindingId;
    private String identityTenantId;
    private String identityClientId;
    private String identityOwnerJiacn;
    private String canonicalAgentId;
    private String canonicalType;
    private String lifecycleStatus;
    private Boolean agentReferenceValid;
    private Long runtimeId;
    private String runtimeTenantId;
    private String runtimeClientId;
    private String runtimeOwnerJiacn;
    private Long runtimeBindingId;
    private String runtimeAgentId;
    private String runtimeAbilities;
    private String runtimeStatus;
}
