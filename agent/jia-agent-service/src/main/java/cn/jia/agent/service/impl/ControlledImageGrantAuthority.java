package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import cn.jia.agent.service.ControlledImageExecutionSessionLookup;
import jakarta.inject.Named;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;
import java.util.Set;

import static cn.jia.agent.service.AgentTaskExecutionGrantException.Reason;

/** Lower-level, one-way cost authority verifier. It never calls Grant, Consent, Execution or Chat services. */
@Named
public final class ControlledImageGrantAuthority {
    static final String LOCATOR_PREFIX="mmd-ci-v1:";
    private final AgentTaskExecutionGrantDao grants;
    private final AgentTaskProviderCostConsentDao consents;
    private final ControlledImageProviderOperatorPolicy policies;
    private final ObjectProvider<ControlledImageExecutionSessionLookup> sessions;

    public ControlledImageGrantAuthority(AgentTaskExecutionGrantDao grants,
            AgentTaskProviderCostConsentDao consents,
            ControlledImageProviderOperatorPolicy policies,
            ObjectProvider<ControlledImageExecutionSessionLookup> sessions) {
        this.grants=Objects.requireNonNull(grants);this.consents=Objects.requireNonNull(consents);
        this.policies=Objects.requireNonNull(policies);this.sessions=Objects.requireNonNull(sessions);
    }

    AgentTaskExecutionGrantEntity lockGrant(AgentTaskExecutionGrantService.Scope scope,String taskId,
            String grantId) {
        var row=grants.findByGrantForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,grantId);
        if (row==null) throw denied(Reason.NOT_FOUND);
        return row;
    }

    AgentTaskProviderCostConsentEntity lockConsent(AgentTaskProviderCostConsentService.Scope scope,
            String taskId,String consentId) {
        var row=consents.findByConsentForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,consentId);
        if (row==null) throw denied(Reason.NOT_FOUND);
        return row;
    }

    Authority verify(AgentTaskExecutionGrantService.Scope scope, AgentTaskMetaEntity root,
            AgentTaskExecutionGrantEntity grant, String purpose, String executionId, String runId,
            String expectedRuntimeInstanceId) {
        if (scope==null || root==null || grant==null || !"ACTIVE".equals(grant.getState())
                || !Objects.equals(root.getTaskId(),grant.getTaskId())
                || !Objects.equals(root.getAssignedAgentId(),grant.getTargetAgentId())
                || grant.getCostAuthorizationRef()==null
                || !grant.getCostAuthorizationRef().matches("mmd-ci-v1:consent_[0-9a-f]{32}"))
            throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        String consentId=grant.getCostAuthorizationRef().substring(LOCATOR_PREFIX.length());
        var consent=consents.findByConsentForUpdate(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                grant.getTaskId(),consentId);
        if (consent==null || !sameTuple(grant,consent)) throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        String required=switch(purpose) {
            case "NEW_EXECUTION" -> "BOUND";
            case "PROVIDER_START" -> "RESERVED";
            case "EXISTING_RUN" -> "CONSUMED";
            default -> throw denied(Reason.BAD_REQUEST);
        };
        if (!required.equals(consent.getState())) throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        if (!"NEW_EXECUTION".equals(purpose)
                && (!Objects.equals(executionId,consent.getReservedExecutionId())
                    || !Objects.equals(runId,consent.getReservedRunId())))
            throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        if ("EXISTING_RUN".equals(purpose)) return new Authority(consent,null);
        return new Authority(consent,requireCurrentSession(scope,grant,consent,expectedRuntimeInstanceId));
    }


    void requireBindable(AgentTaskExecutionGrantService.Scope scope,AgentTaskExecutionGrantEntity grant,
            AgentTaskProviderCostConsentEntity consent) {
        if(scope==null||grant==null||consent==null||!"ISSUED".equals(consent.getState())
                ||!Objects.equals(grant.getTenantId(),consent.getTenantId())
                ||!Objects.equals(grant.getClientId(),consent.getClientId())
                ||!Objects.equals(grant.getOwnerJiacn(),consent.getOwnerJiacn())
                ||!Objects.equals(grant.getTaskId(),consent.getTaskId())
                ||!Objects.equals(grant.getTargetAgentId(),consent.getTargetAgentId())
                ||!Objects.equals(grant.getRequirementRevision(),consent.getRequirementRevision())
                ||!Objects.equals(grant.getIdempotencyKey(),consent.getAssignmentIdempotencyKey())
                ||!Objects.equals(grant.getSourceBusinessActionId(),
                        "ASSIGN_AND_START:"+consent.getAssignmentIdempotencyKey())
                ||!Objects.equals(grant.getRequestHash(),consent.getAssignmentBaseHash()))
            throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        requireCurrentSession(scope,grant,consent,null);
    }

    private ControlledImageExecutionSessionLookup.Snapshot requireCurrentSession(
            AgentTaskExecutionGrantService.Scope scope,AgentTaskExecutionGrantEntity grant,
            AgentTaskProviderCostConsentEntity consent,String expectedRuntimeInstanceId) {
        var consentScope=new AgentTaskProviderCostConsentService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        var policy=policies.requireCurrent(consentScope,grant.getTargetAgentId(),consent.getBindingId(),
                consent.getBindingEpoch(),System.currentTimeMillis());
        if (!Objects.equals(policy.providerLane(),consent.getProviderLane())
                || !Objects.equals(policy.modelId(),consent.getModelId())
                || !Objects.equals(policy.custody(),consent.getCustody())
                || !Objects.equals(policy.issuer(),consent.getOperatorIssuer())
                || !Objects.equals(policy.policyRevision(),consent.getOperatorPolicyRevision())
                || !Objects.equals(policy.pricingMode(),consent.getPricingMode())
                || policy.maxOutboundRequestAttempts()!=consent.getMaxOutboundRequestAttempts()
                || policy.expiresAt()!=consent.getExpiresAt()) throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        var lookup=sessions.getIfUnique();
        if (lookup==null) throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        var current=lookup.current(new ControlledImageExecutionSessionLookup.Scope(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),grant.getTargetAgentId()));
        if (current==null || current.state()!=ControlledImageExecutionSessionLookup.State.READY
                || !Objects.equals(current.schemaVersion(),1)
                || !Objects.equals(current.transport(),"PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V2")
                || !Objects.equals(current.supportedOperations(),java.util.List.of("GENERATE_IMAGE"))
                || !Objects.equals(current.bindingId(),consent.getBindingId())
                || !Objects.equals(current.bindingEpoch(),consent.getBindingEpoch())
                || !Objects.equals(current.modelId(),consent.getModelId())
                || !Objects.equals(current.providerLane(),consent.getProviderLane())
                || !Objects.equals(current.maxInputItems(),16)
                || !Objects.equals(current.maxOutboundRequestAttempts(),1)
                || !Objects.equals(current.precallFenceVersion(),1)
                || expectedRuntimeInstanceId!=null && !current.matchesRuntime(expectedRuntimeInstanceId))
            throw denied(Reason.PAID_EXECUTION_NOT_AUTHORIZED);
        return current;
    }

    boolean isPersistedAuthorized(AgentTaskExecutionGrantEntity grant) {
        if (grant==null || grant.getCostAuthorizationRef()==null
                || !grant.getCostAuthorizationRef().matches("mmd-ci-v1:consent_[0-9a-f]{32}"))
            return false;
        String consentId=grant.getCostAuthorizationRef().substring(LOCATOR_PREFIX.length());
        var consent=consents.findByConsent(grant.getTenantId(),grant.getClientId(),grant.getOwnerJiacn(),
                grant.getTaskId(),consentId);
        return consent!=null && Set.of("BOUND","RESERVED","CONSUMED").contains(consent.getState())
                && sameTuple(grant,consent);
    }

    void bindLocator(AgentTaskExecutionGrantService.Scope scope,AgentTaskExecutionGrantEntity grant,
            AgentTaskProviderCostConsentEntity consent) {
        String locator=LOCATOR_PREFIX+consent.getConsentId();
        if (!locator.matches("mmd-ci-v1:consent_[0-9a-f]{32}")) throw denied(Reason.BAD_REQUEST);
        if (grant.getCostAuthorizationRef()!=null) {
            if (!Objects.equals(locator,grant.getCostAuthorizationRef())) throw denied(Reason.CONFLICT);
            return;
        }
        if (!grants.setCostAuthorizationRef(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                grant.getTaskId(),grant.getGrantId(),grant.getGrantVersion(),locator)) throw denied(Reason.CONFLICT);
        grant.setCostAuthorizationRef(locator);
    }

    private static boolean sameTuple(AgentTaskExecutionGrantEntity grant,
            AgentTaskProviderCostConsentEntity consent) {
        return Objects.equals(grant.getTenantId(),consent.getTenantId())
                && Objects.equals(grant.getClientId(),consent.getClientId())
                && Objects.equals(grant.getOwnerJiacn(),consent.getOwnerJiacn())
                && Objects.equals(grant.getTaskId(),consent.getTaskId())
                && Objects.equals(grant.getTargetAgentId(),consent.getTargetAgentId())
                && Objects.equals(grant.getGrantId(),consent.getBoundGrantId())
                && Objects.equals(grant.getGrantVersion(),consent.getBoundGrantVersion())
                && Objects.equals(grant.getAssignmentRevision(),consent.getBoundAssignmentRevision())
                && Objects.equals(grant.getRequirementRevision(),consent.getRequirementRevision())
                && Objects.equals(grant.getIdempotencyKey(),consent.getAssignmentIdempotencyKey())
                && Objects.equals(grant.getSourceBusinessActionId(),
                        "ASSIGN_AND_START:"+consent.getAssignmentIdempotencyKey())
                && Objects.equals(grant.getRequestHash(),consent.getAssignmentBaseHash());
    }
    private static AgentTaskExecutionGrantException denied(Reason reason) {
        return new AgentTaskExecutionGrantException(reason,"Controlled image authority is unavailable or stale");
    }
    record Authority(AgentTaskProviderCostConsentEntity consent,
            ControlledImageExecutionSessionLookup.Snapshot session) { }
}
