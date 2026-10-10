package cn.jia.agent.hosting;

import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/** Internal installation authorization and exact intent linkage. Legacy keys are audit/refund-only, never execution authority. */
@Component
public final class ManagedHostingCredentials {
    private final ObjectProvider<ApiKeyService> keys;
    private final EconomyHostingRentMapper rent;
    private final HostingRentOwnerResolver owners;
    private final AgentIdentityService identities;
    private final AgentPersonaBindingDao bindings;
    private final TransactionTemplate transactions;
    private final cn.jia.agent.service.AgentRuntimeV1Service runtime;
    public ManagedHostingCredentials(ObjectProvider<ApiKeyService> keys, EconomyHostingRentMapper rent,
            HostingRentOwnerResolver owners, AgentIdentityService identities, AgentPersonaBindingDao bindings,
            PlatformTransactionManager manager, cn.jia.agent.service.AgentRuntimeV1Service runtime) {
        this.keys = keys; this.rent = rent; this.owners = owners; this.identities = identities;
        this.runtime = runtime; this.bindings = bindings; this.transactions = new TransactionTemplate(manager);
    }
    public boolean available() { return runtime != null; }

    /** No secret/key creation. Installation authorization and durable operation linkage commit together. */
    public void ensureInstallation(ManagedHostingProvisioner.Preparation p, ManagedHostingProvisioner.Candidate candidate) {
        transactions.executeWithoutResult(status -> {
            if (p == null || candidate == null || candidate.provisionGeneration() <= 0) throw unavailable();
            var installed = runtime.ensureInstallation(p.tenantId(), p.clientId(), p.ownerJiacn(),
                    new cn.jia.agent.entity.AgentRuntimeV1InstallationRequest(candidate.installationId(), p.agentId(), "1",
                            candidate.manifestSha256(), candidate.enrollmentSecretSha256(), candidate.enrollmentExpiresAt()),
                    System.currentTimeMillis());
            if (installed == null || !candidate.installationId().equals(installed.installationId())
                    || !candidate.manifestSha256().equals(installed.manifestSha256())
                    || !p.tenantId().equals(installed.tenantId()) || !p.clientId().equals(installed.clientId())
                    || !p.agentId().equals(installed.canonicalAgentId())) throw unavailable();
            var intent = rent.selectIntentForUpdate(p.tenantId(), p.clientId(), p.intentId());
            if (intent == null || !p.intentId().equals(intent.getIntentId())
                    || !p.tenantId().equals(intent.getTenantId()) || !p.clientId().equals(intent.getClientId())
                    || !"INITIAL".equals(intent.getQuotePurpose()) || !"USER".equals(intent.getPrincipalType())
                    || !p.agentId().equals(intent.getAgentId()) || !p.leaseId().equals(intent.getLeaseId())
                    || !Objects.equals(p.reservedAt(), intent.getReservedAt()) || intent.getRefundTransactionId() != null
                    || !("PROVISIONING_UNKNOWN".equals(intent.getStatus()) || "ACTIVE".equals(intent.getStatus()))) throw unavailable();
            var actor = new HostingRentHttp.Actor(intent.getPrincipalId(), p.tenantId(), p.clientId(), p.ownerJiacn());
            if (!p.ownerJiacn().equals(owners.requireOwner(actor))) throw unavailable();
            var lease = rent.selectLeaseForUpdate(p.tenantId(), p.clientId(), p.leaseId());
            if (lease == null || !p.leaseId().equals(lease.getLeaseId()) || !p.agentId().equals(lease.getAgentId())
                    || !p.bindingId().equals(lease.getBindingId()) || !intent.getPersonaCode().equals(lease.getPersonaCode())
                    || !intent.getPrincipalId().equals(lease.getPrincipalId()) || !"USER".equals(lease.getPrincipalType())) throw unavailable();
            long bindingId = Long.parseLong(p.bindingId());
            if (!Long.toString(bindingId).equals(p.bindingId()) || bindingId <= 0) throw unavailable();
            var locked = bindings.findByIdForUpdate(bindingId);
            if (locked == null || !Long.valueOf(bindingId).equals(locked.getId()) || !Integer.valueOf(1).equals(locked.getStatus())
                    || !p.agentId().equals(locked.getAgentId()) || !p.tenantId().equals(locked.getTenantId())
                    || !p.clientId().equals(locked.getClientId()) || !p.ownerJiacn().equals(locked.getJiacn())
                    || !intent.getPersonaCode().equals(locked.getPersonaCode())) throw unavailable();
            var identity = identities.requireRegistrationIdentityInScope(p.tenantId(), p.clientId(), p.ownerJiacn(), p.agentId());
            var binding = identities.requireActiveBinding(identity, null);
            if (identity == null || binding == null || !p.agentId().equals(identity.getCanonicalAgentId())
                    || !Long.valueOf(bindingId).equals(identity.getBindingId()) || !Long.valueOf(bindingId).equals(binding.getId())) throw unavailable();
            if (p.operationId().equals(p.intentId())) {
                if (candidate.provisionGeneration() != 1 || p.validUntil() != null || p.requestedAt() != p.reservedAt()
                        || !"PROVISIONING_UNKNOWN".equals(intent.getStatus()) || intent.getCaptureTransactionId() != null
                        || !"PROVISIONING".equals(lease.getStatus()) || !p.intentId().equals(lease.getLatestIntentId())) throw unavailable();
                if (intent.getRuntimeInstallationId() == null) {
                    if (intent.getRuntimeManifestSha256() != null || !Long.valueOf(0).equals(intent.getRuntimeProvisionGeneration())
                            || rent.attachRuntimeInstallation(p.tenantId(), p.clientId(), p.intentId(), candidate.installationId(),
                                    candidate.manifestSha256()) != 1) throw unavailable();
                } else if (!matches(intent, candidate) || !Long.valueOf(1).equals(intent.getRuntimeProvisionGeneration())) throw unavailable();
            } else {
                var free = rent.selectReprovisionForUpdate(p.tenantId(), p.clientId(), p.operationId());
                if (!"ACTIVE".equals(intent.getStatus()) || !"ACTIVE".equals(installed.status()) || !"ACTIVE".equals(lease.getStatus())
                        || free == null || !p.operationId().equals(free.getRequestId()) || !p.intentId().equals(free.getIntentId())
                        || !p.agentId().equals(free.getAgentId()) || !p.leaseId().equals(free.getLeaseId())
                        || !intent.getPrincipalId().equals(free.getPrincipalId()) || !"PROVISIONING_UNKNOWN".equals(free.getStatus())
                        || !Objects.equals(p.requestedAt(), free.getRequestedAt()) || !Objects.equals(p.validUntil(), free.getPaidThrough())
                        || !Objects.equals(p.validUntil(), lease.getPaidThrough()) || p.validUntil() == null
                        || p.validUntil() <= System.currentTimeMillis() || !matches(intent, candidate)
                        || intent.getRuntimeProvisionGeneration() == null || intent.getRuntimeProvisionGeneration() <= 0) throw unavailable();
                if (free.getRuntimeTargetGeneration() == null) {
                    if (candidate.provisionGeneration() <= intent.getRuntimeProvisionGeneration()
                            || rent.advanceRuntimeGeneration(p.tenantId(), p.clientId(), p.intentId(), candidate.installationId(),
                                    candidate.manifestSha256(), intent.getRuntimeProvisionGeneration(), candidate.provisionGeneration()) != 1
                            || rent.attachRuntimeTarget(p.tenantId(), p.clientId(), p.operationId(), candidate.provisionGeneration()) != 1) throw unavailable();
                } else if (!Long.valueOf(candidate.provisionGeneration()).equals(free.getRuntimeTargetGeneration())
                        || !Long.valueOf(candidate.provisionGeneration()).equals(intent.getRuntimeProvisionGeneration())) throw unavailable();
            }
        });
    }

    private static boolean matches(EconomyHostingProvisioningIntentEntity intent, ManagedHostingProvisioner.Candidate candidate) {
        return candidate.installationId().equals(intent.getRuntimeInstallationId())
                && candidate.manifestSha256().equals(intent.getRuntimeManifestSha256());
    }

    void disableForRefund(
            ManagedHostingProvisioner.Preparation preparation,
            EconomyHostingProvisioningIntentEntity intent) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) throw unavailable();
        if (preparation == null || intent == null || intent.getId() == null
                || !"FAILED_NO_EFFECT".equals(intent.getStatus())
                || !"INITIAL".equals(intent.getQuotePurpose())
                || !"USER".equals(intent.getPrincipalType())
                || !preparation.intentId().equals(intent.getIntentId())
                || !preparation.intentId().equals(preparation.operationId())
                || !preparation.tenantId().equals(intent.getTenantId())
                || !preparation.clientId().equals(intent.getClientId())
                || !preparation.agentId().equals(intent.getAgentId())
                || !preparation.leaseId().equals(intent.getLeaseId())
                || !Objects.equals(preparation.reservedAt(), intent.getReservedAt())
                || intent.getManagedApiKeyId() == null || intent.getManagedApiKeyId().isBlank()
                || intent.getCaptureTransactionId() != null || intent.getRefundTransactionId() != null
                || intent.getServiceReadyAt() != null) throw unavailable();
        ApiKeyService service = keys.getIfAvailable();
        if (service == null || !service.disableManagedKey(intent.getManagedApiKeyId(),
                preparation.tenantId(), preparation.clientId(), preparation.ownerJiacn(),
                managedKeyName(preparation.intentId()))) throw unavailable();
    }

    private static String managedKeyName(String intentId) { return "hosting:" + intentId; }

    private static IllegalStateException unavailable() { return new IllegalStateException("Managed credential/association not ready"); }
}
