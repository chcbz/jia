package cn.jia.agent.service.impl;

import cn.jia.agent.config.ControlledImageProviderProperties;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import cn.jia.agent.service.NativeProviderCredentialBindingLookup;
import jakarta.inject.Named;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Objects;

/** Exact intersection of current authenticated declaration and server operator delegation. */
@Named
public final class ControlledImageProviderOperatorPolicy {
    public static final String PROVIDER_LANE = "CONTROLLED_IMAGE_HTTP_V1";
    public static final String PRICING_MODE = "UNPRICED_EXTERNAL_ACCOUNT";

    private final ControlledImageProviderProperties properties;
    private final ObjectProvider<NativeProviderCredentialBindingLookup> lookups;

    public ControlledImageProviderOperatorPolicy(ControlledImageProviderProperties properties,
            ObjectProvider<NativeProviderCredentialBindingLookup> lookups) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.lookups = Objects.requireNonNull(lookups, "lookups");
    }

    Policy requireCurrent(AgentTaskProviderCostConsentService.Scope scope, String targetAgentId,
            String requestedBindingId, long requestedBindingEpoch, long now) {
        if (!properties.isEnabled() || properties.getOperatorPolicies().isEmpty()) {
            throw unavailable();
        }
        NativeProviderCredentialBindingLookup lookup = lookups.getIfUnique();
        if (lookup == null) throw unavailable();
        final NativeProviderCredentialBindingLookup.Snapshot current;
        try {
            current = lookup.current(new NativeProviderCredentialBindingLookup.Scope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), targetAgentId));
        } catch (NativeProviderCredentialBindingLookup.SourceUnavailable unavailable) {
            throw new PolicyFailure(Reason.SOURCE_UNAVAILABLE, unavailable);
        }
        if (current == null || current.state() != NativeProviderCredentialBindingLookup.State.READY
                || current.schemaVersion() == null || current.schemaVersion() != 1
                || !PROVIDER_LANE.equals(current.providerLane())
                || current.bindingEpoch() == null || current.bindingEpoch() < 1
                || current.maxInputItems() == null || current.maxInputItems() != 16
                || current.maxOutboundRequestAttempts() == null
                || current.maxOutboundRequestAttempts() != 1
                || current.precallFenceVersion() == null || current.precallFenceVersion() != 1) {
            throw new PolicyFailure(Reason.CONFLICT, null);
        }
        if (!same(requestedBindingId, current.bindingId())
                || requestedBindingEpoch != current.bindingEpoch()) {
            throw new PolicyFailure(Reason.CONFLICT, null);
        }
        List<ControlledImageProviderProperties.OperatorPolicy> matches = properties
                .getOperatorPolicies().stream().filter(policy -> matches(policy, scope,
                        targetAgentId, current)).toList();
        if (matches.size() != 1) {
            throw new PolicyFailure(matches.isEmpty() ? Reason.FORBIDDEN
                    : Reason.SOURCE_UNAVAILABLE, null);
        }
        ControlledImageProviderProperties.OperatorPolicy value = matches.getFirst();
        requireValid(value);
        if (value.getExpiresAt() <= now) throw new PolicyFailure(Reason.CONFLICT, null);
        return new Policy(value.getProviderLane(), value.getBindingId(), value.getBindingEpoch(),
                value.getModelId(), value.getCustody(), value.getIssuer(),
                value.getPolicyRevision(), PRICING_MODE, 1, value.getExpiresAt());
    }

    private static boolean matches(ControlledImageProviderProperties.OperatorPolicy policy,
            AgentTaskProviderCostConsentService.Scope scope, String target,
            NativeProviderCredentialBindingLookup.Snapshot current) {
        return policy != null && Objects.equals(scope.tenantId(), policy.getTenantId())
                && Objects.equals(scope.clientId(), policy.getClientId())
                && Objects.equals(scope.ownerJiacn(), policy.getOwnerJiacn())
                && Objects.equals(target, policy.getTargetAgentId())
                && Objects.equals(current.providerLane(), policy.getProviderLane())
                && Objects.equals(current.bindingId(), policy.getBindingId())
                && Objects.equals(current.bindingEpoch(), policy.getBindingEpoch())
                && Objects.equals(current.modelId(), policy.getModelId());
    }

    private static void requireValid(ControlledImageProviderProperties.OperatorPolicy policy) {
        if (!"0".equals(policy.getTenantId()) || !valid(policy.getClientId(),50)
                || !valid(policy.getOwnerJiacn(),50) || "0".equals(policy.getOwnerJiacn())
                || !valid(policy.getTargetAgentId(),100)
                || !PROVIDER_LANE.equals(policy.getProviderLane())
                || !valid(policy.getBindingId(),100) || policy.getBindingEpoch()==null
                || policy.getBindingEpoch()<1 || !valid(policy.getModelId(),100)
                || !valid(policy.getCustody(),50) || !valid(policy.getIssuer(),100)
                || !valid(policy.getPolicyRevision(),100) || policy.getExpiresAt()==null
                || policy.getExpiresAt()<1
                || !Boolean.TRUE.equals(policy.getAllowUnpricedExternalAccount())
                || !Objects.equals(1,policy.getMaxOutboundRequestAttempts())) {
            throw unavailable();
        }
    }

    private static boolean valid(String value,int max) {
        return value!=null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0,value.length())<=max
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static boolean same(String left,String right) {
        return left != null && left.equals(right);
    }
    private static PolicyFailure unavailable() {
        return new PolicyFailure(Reason.SOURCE_UNAVAILABLE, null);
    }

    record Policy(String providerLane, String bindingId, long bindingEpoch, String modelId,
            String custody, String issuer, String policyRevision, String pricingMode,
            int maxOutboundRequestAttempts, long expiresAt) { }
    enum Reason { FORBIDDEN, CONFLICT, SOURCE_UNAVAILABLE }
    static final class PolicyFailure extends RuntimeException {
        private final Reason reason;
        PolicyFailure(Reason reason, Throwable cause) {
            super(reason.name(), cause); this.reason=reason;
        }
        Reason reason() { return reason; }
    }
}
