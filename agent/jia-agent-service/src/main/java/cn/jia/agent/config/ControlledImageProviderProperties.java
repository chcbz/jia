package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Explicit operator delegation; defaults intentionally authorize nothing. */
@ConfigurationProperties(prefix = "agent.controlled-image-provider")
public class ControlledImageProviderProperties {
    private boolean enabled;
    private List<OperatorPolicy> operatorPolicies = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<OperatorPolicy> getOperatorPolicies() { return operatorPolicies; }
    public void setOperatorPolicies(List<OperatorPolicy> operatorPolicies) {
        this.operatorPolicies = operatorPolicies == null ? new ArrayList<>() : operatorPolicies;
    }

    public static class OperatorPolicy {
        private String tenantId;
        private String clientId;
        private String ownerJiacn;
        private String targetAgentId;
        private String providerLane;
        private String bindingId;
        private Long bindingEpoch;
        private String modelId;
        private String custody;
        private String issuer;
        private String policyRevision;
        private Long expiresAt;
        private Boolean allowUnpricedExternalAccount;
        private Integer maxOutboundRequestAttempts;

        public String getTenantId() { return tenantId; }
        public void setTenantId(String value) { tenantId=value; }
        public String getClientId() { return clientId; }
        public void setClientId(String value) { clientId=value; }
        public String getOwnerJiacn() { return ownerJiacn; }
        public void setOwnerJiacn(String value) { ownerJiacn=value; }
        public String getTargetAgentId() { return targetAgentId; }
        public void setTargetAgentId(String value) { targetAgentId=value; }
        public String getProviderLane() { return providerLane; }
        public void setProviderLane(String value) { providerLane=value; }
        public String getBindingId() { return bindingId; }
        public void setBindingId(String value) { bindingId=value; }
        public Long getBindingEpoch() { return bindingEpoch; }
        public void setBindingEpoch(Long value) { bindingEpoch=value; }
        public String getModelId() { return modelId; }
        public void setModelId(String value) { modelId=value; }
        public String getCustody() { return custody; }
        public void setCustody(String value) { custody=value; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String value) { issuer=value; }
        public String getPolicyRevision() { return policyRevision; }
        public void setPolicyRevision(String value) { policyRevision=value; }
        public Long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Long value) { expiresAt=value; }
        public Boolean getAllowUnpricedExternalAccount() { return allowUnpricedExternalAccount; }
        public void setAllowUnpricedExternalAccount(Boolean value) { allowUnpricedExternalAccount=value; }
        public Integer getMaxOutboundRequestAttempts() { return maxOutboundRequestAttempts; }
        public void setMaxOutboundRequestAttempts(Integer value) { maxOutboundRequestAttempts=value; }
    }
}
