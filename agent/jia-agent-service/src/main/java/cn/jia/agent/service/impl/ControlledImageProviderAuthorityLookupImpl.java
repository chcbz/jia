package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import cn.jia.agent.service.ControlledImageProviderAuthorityLookup;
import jakarta.inject.Named;
import java.util.Objects;

/** Current, non-secret operator policy fact for capability projection. */
@Named
public final class ControlledImageProviderAuthorityLookupImpl
        implements ControlledImageProviderAuthorityLookup {
    private final ControlledImageProviderOperatorPolicy policies;
    public ControlledImageProviderAuthorityLookupImpl(ControlledImageProviderOperatorPolicy policies) {
        this.policies=Objects.requireNonNull(policies);
    }
    @Override public Snapshot current(Scope scope,String targetAgentId,String bindingId,long bindingEpoch) {
        if(scope==null)throw new SourceUnavailable(new IllegalArgumentException("scope"));
        try {
            var policy=policies.requireCurrent(new AgentTaskProviderCostConsentService.Scope(
                    scope.tenantId(),scope.clientId(),scope.ownerJiacn()),targetAgentId,bindingId,
                    bindingEpoch,System.currentTimeMillis());
            return new Snapshot(State.READY,policy.providerLane(),policy.bindingId(),
                    Long.toString(policy.bindingEpoch()),policy.modelId(),
                    policy.maxOutboundRequestAttempts());
        } catch (ControlledImageProviderOperatorPolicy.PolicyFailure failure) {
            if(failure.reason()==ControlledImageProviderOperatorPolicy.Reason.SOURCE_UNAVAILABLE)
                throw new SourceUnavailable(failure);
            return new Snapshot(State.UNAVAILABLE,null,null,null,null,null);
        } catch (RuntimeException failure) { throw new SourceUnavailable(failure); }
    }
}
