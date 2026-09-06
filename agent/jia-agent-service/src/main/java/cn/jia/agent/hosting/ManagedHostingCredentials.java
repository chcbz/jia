package cn.jia.agent.hosting;

import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;
import java.util.UUID;

/** Uses the existing identity and API-key services under explicitly proved scope; no legacy fallbacks. */
@Component
public final class ManagedHostingCredentials {
    private final ObjectProvider<ApiKeyService> keys;
    private final EconomyHostingRentMapper rent;
    private final HostingRentOwnerResolver owners;
    private final AgentIdentityService identities;
    private final AgentPersonaBindingDao bindings;
    private final TransactionTemplate transactions;
    public ManagedHostingCredentials(ObjectProvider<ApiKeyService> keys, EconomyHostingRentMapper rent,
            HostingRentOwnerResolver owners, AgentIdentityService identities, AgentPersonaBindingDao bindings,
            PlatformTransactionManager manager) {
        this.keys = keys; this.rent = rent; this.owners = owners; this.identities = identities;
        this.bindings = bindings; this.transactions = new TransactionTemplate(manager);
    }
    public boolean available() { return keys.getIfAvailable() != null; }

    public String credential(ManagedHostingProvisioner.Preparation p) {
        return transactions.execute(status -> {
            var intent = rent.selectIntentForUpdate(p.tenantId(), p.clientId(), p.intentId());
            if (intent == null || !"INITIAL".equals(intent.getQuotePurpose()) || !"USER".equals(intent.getPrincipalType())
                    || !p.agentId().equals(intent.getAgentId()) || !p.leaseId().equals(intent.getLeaseId())
                    || !Objects.equals(p.reservedAt(), intent.getReservedAt())
                    || !("PROVISIONING_UNKNOWN".equals(intent.getStatus()) || "ACTIVE".equals(intent.getStatus()))) throw unavailable();
            var actor = new HostingRentHttp.Actor(intent.getPrincipalId(), p.tenantId(), p.clientId());
            if (!p.ownerJiacn().equals(owners.requireOwner(actor))) throw unavailable();
            var lease = rent.selectLeaseForUpdate(p.tenantId(), p.clientId(), p.leaseId());
            if (lease == null || !p.agentId().equals(lease.getAgentId()) || !p.bindingId().equals(lease.getBindingId())
                    || !intent.getPrincipalId().equals(lease.getPrincipalId()) || !"USER".equals(lease.getPrincipalType())) throw unavailable();
            var locked = bindings.findByIdForUpdate(Long.parseLong(p.bindingId()));
            if (locked == null || !Integer.valueOf(1).equals(locked.getStatus()) || !p.agentId().equals(locked.getAgentId())
                    || !p.clientId().equals(locked.getClientId()) || !p.ownerJiacn().equals(locked.getJiacn())) throw unavailable();
            var identity = identities.requireRegistrationIdentityInScope(p.tenantId(), p.clientId(), p.ownerJiacn(), p.agentId());
            var binding = identities.requireActiveBinding(identity, null);
            if (!p.agentId().equals(identity.getCanonicalAgentId()) || !p.bindingId().equals(binding.getId().toString())) throw unavailable();
            if (p.operationId().equals(p.intentId())) {
                if (!"PROVISIONING".equals(lease.getStatus()) || !p.intentId().equals(lease.getLatestIntentId())) throw unavailable();
            } else {
                var free = rent.selectReprovisionForUpdate(p.tenantId(), p.clientId(), p.operationId());
                if (!"ACTIVE".equals(lease.getStatus()) || free == null || !p.intentId().equals(free.getIntentId())
                        || !p.agentId().equals(free.getAgentId()) || !p.leaseId().equals(free.getLeaseId())
                        || !intent.getPrincipalId().equals(free.getPrincipalId()) || !"PROVISIONING_UNKNOWN".equals(free.getStatus())
                        || !Objects.equals(p.requestedAt(), free.getRequestedAt()) || !Objects.equals(p.validUntil(), free.getPaidThrough())) throw unavailable();
            }
            ApiKeyService service = keys.getIfAvailable();
            if (service == null) throw unavailable();
            String name = managedKeyName(p.intentId());
            OauthApiKeyEntity key;
            if (intent.getManagedApiKeyId() == null) {
                // Key + association reference commit together. Only existing OAuth DB service, never external I/O.
                key = new OauthApiKeyEntity();
                key.setTenantId(p.tenantId()); key.setClientId(p.clientId()); key.setJiacn(p.ownerJiacn());
                key.setKeyName(name); key.setApiKey("cdx_" + UUID.randomUUID().toString().replace("-", ""));
                key.setStatus(1); key.setDescription("Managed canonical Agent " + p.agentId());
                key = service.create(key);
                if (key == null || key.getId() == null || rent.attachManagedKey(p.tenantId(), p.clientId(), p.intentId(), key.getId()) != 1) throw unavailable();
            } else key = service.get(intent.getManagedApiKeyId());
            if (key == null || !p.tenantId().equals(key.getTenantId()) || !p.clientId().equals(key.getClientId())
                    || !p.ownerJiacn().equals(key.getJiacn()) || !name.equals(key.getKeyName())
                    || !Integer.valueOf(1).equals(key.getStatus())
                    || key.getExpireTime() != null && key.getExpireTime() <= System.currentTimeMillis()) throw unavailable();
            HostingRentHttp.exact(key.getApiKey(), 256);
            return key.getApiKey();
        });
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
