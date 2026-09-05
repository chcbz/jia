package cn.jia.agent.hosting;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.mapper.AgentHostingRentBindingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.entity.EconomyHostingRentPlanEntity;
import cn.jia.economy.entity.EconomyHostingRentQuoteEntity;
import cn.jia.economy.hosting.*;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static cn.jia.agent.hosting.HostingRentHttp.*;

/** Database-only HTTP application boundary. The legacy fallback-based bind service is never called. */
@Service
public final class HostingRentApplicationService {
    private static final String PLAN_ID = "agent-hosting-preview";
    private final AgentHostingRentProperties properties;
    private final EconomyPreviewGate preview;
    private final HostingRentOwnerResolver owners;
    private final EconomyHostingRentMapper rent;
    private final HostingRentLedgerService ledger;
    private final AgentHostingRentBindingMapper roots;
    private final AgentPersonaBindingDao bindings;
    private final AgentRuntimeDao runtimes;
    private final AgentIdentityService identities;
    private final ObjectProvider<ManagedHostingProvisioner> provisioners;
    private final HostingRentReconciler reconciler;
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads;
    private final boolean schemaEnabled;

    public HostingRentApplicationService(AgentHostingRentProperties properties, EconomyPreviewGate preview,
            HostingRentOwnerResolver owners, EconomyHostingRentMapper rent, HostingRentLedgerService ledger,
            AgentHostingRentBindingMapper roots, AgentPersonaBindingDao bindings, AgentRuntimeDao runtimes,
            AgentIdentityService identities, ObjectProvider<ManagedHostingProvisioner> provisioners,
            HostingRentReconciler reconciler, PlatformTransactionManager transactionManager,
            @org.springframework.beans.factory.annotation.Value("${economy.hosting-rent.schema-enabled:false}") boolean schemaEnabled) {
        this.properties = properties; this.preview = preview; this.owners = owners; this.rent = rent;
        this.ledger = ledger; this.roots = roots; this.bindings = bindings; this.runtimes = runtimes;
        this.identities = identities; this.provisioners = provisioners; this.reconciler = reconciler;
        this.transactions = new TransactionTemplate(transactionManager);
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
        this.reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.schemaEnabled = schemaEnabled;
    }

    public QuoteView quote(Actor actor, String personaCode, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor);
        key(idempotencyKey); exact(personaCode, 100);
        if (!"INITIAL".equals(body.get("purpose"))) throw badRequest();
        String requestedAgent = body.get("agentId");
        if (requestedAgent != null) requireCanonicalShape(requestedAgent);
        byte[] hash = hash("INITIAL:" + personaCode, body);
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            EconomyHostingRentQuoteEntity replay = actorQuote(actor, idempotencyKey);
            if (replay != null) return quoteView(ledger.quote(quoteCommand(actor, idempotencyKey, hash,
                    personaCode, replay.getAgentId(), replay.getPlanId(), replay.getPlanVersion(),
                    HostingRentQuotePurpose.INITIAL, null, null)));
            requireProvisioner();
            // Match the foundation quote lock order: plan before any lease/persona root.
            EconomyHostingRentPlanEntity plan = plan(actor);
            AgentPersonaEntity persona = persona(personaCode);
            AgentPersonaBindingEntity existing = currentBinding(actor, owner, personaCode);
            String canonical;
            if (existing != null) {
                if (requestedAgent == null || !requestedAgent.equals(existing.getAgentId())) {
                    throw conflict("HOSTING_RENT_AGENT_CONFIRMATION_REQUIRED");
                }
                requireExisting(actor, owner, existing.getAgentId(), personaCode);
                canonical = existing.getAgentId();
            } else {
                if (requestedAgent != null) throw forbidden();
                canonical = "agt_" + UUID.randomUUID().toString().replace("-", "");
            }
            // Proposed ID is stored ONLY in this quote. No binding, registry or runtime row yet.
            return quoteView(ledger.quote(quoteCommand(actor, idempotencyKey, hash, persona.getPersonaCode(),
                    canonical, plan.getPlanId(), plan.getPlanVersion(), HostingRentQuotePurpose.INITIAL, null, null)));
        });
    }

    public MutationView bind(Actor actor, String personaCode, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor);
        key(idempotencyKey); exact(personaCode, 100);
        if (!"server".equals(body.get("mode"))) throw badRequest();
        if ("REPROVISION".equals(body.get("hostingAction"))) return reprovision(actor, idempotencyKey, body);
        if (!"INITIAL".equals(body.get("hostingAction"))) throw badRequest();
        if (!Set.of("mode", "hostingAction", "agentId", "quoteId", "expectedPlanVersion",
                "expectedAmountMicro", "expectedPeriodSeconds").containsAll(body.keySet())) throw badRequest();
        requireCanonicalShape(required(body, "agentId"));
        byte[] hash = hash("INITIAL-CONFIRM:" + personaCode, body);
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            EconomyHostingRentQuoteEntity quote = ownedQuote(actor, required(body, "quoteId"));
            confirmation(quote, body, personaCode, "INITIAL");
            EconomyHostingProvisioningIntentEntity prior = rent.selectIntentByQuoteForUpdate(
                    actor.tenantId(), actor.clientId(), quote.getQuoteId());
            if (prior != null) return mutationView(quote.getAgentId(), ledger.reserve(
                    new HostingRentReserveCommand(actor.scope(), actor.principal(), idempotencyKey, hash, quote.getQuoteId())));
            requireProvisioner();
            rent.selectLiveLeaseByAgentForUpdate(actor.tenantId(), actor.clientId(), quote.getAgentId());
            AgentPersonaEntity persona = persona(personaCode);
            AgentPersonaBindingEntity binding = currentBinding(actor, owner, personaCode);
            if (binding != null) requireExisting(actor, owner, quote.getAgentId(), personaCode);
            // Quote/persona/binding roots are locked before REQUIRED W02 funds posting.
            HostingRentMutationReceipt receipt = ledger.reserve(new HostingRentReserveCommand(
                    actor.scope(), actor.principal(), idempotencyKey, hash, quote.getQuoteId()));
            if (binding == null) {
                binding = new AgentPersonaBindingEntity();
                binding.setClientId(actor.clientId()); binding.setTenantId(actor.tenantId()); binding.setJiacn(owner);
                binding.setPersonaCode(personaCode); binding.setAgentId(quote.getAgentId());
                binding.setBoundAt(receipt.occurredAt()); binding.setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
                if (bindings.insert(binding) != 1) throw conflict("HOSTING_RENT_CONFLICT");
                AgentIdentityRegistryEntity identity = identities.provisionOpaqueIdentity(binding,
                        "R01 paid admission: " + receipt.intentId());
                if (!quote.getAgentId().equals(identity.getCanonicalAgentId())
                        || !actor.tenantId().equals(identity.getTenantId()) || !owner.equals(identity.getOwnerJiacn())) {
                    throw forbidden();
                }
                AgentRuntimeEntity runtime = new AgentRuntimeEntity();
                runtime.setAgentId(identity.getCanonicalAgentId()); runtime.setBindingId(binding.getId());
                runtime.setTenantId(actor.tenantId()); runtime.setClientId(actor.clientId()); runtime.setOwnerJiacn(owner);
                runtime.setPersonaCode(personaCode); runtime.setPersonaName(persona.getName()); runtime.setName(persona.getName());
                runtime.setAvatar(persona.getAvatar()); runtime.setAbilities(persona.getAbilities());
                runtime.setStatus(AgentConstants.STATUS_OFFLINE); runtime.setLastSeenAt(receipt.occurredAt());
                if (runtimes.insert(runtime) != 1) throw conflict("HOSTING_RENT_CONFLICT");
            }
            if (binding.getId() == null || rent.attachBinding(actor.tenantId(), actor.clientId(), receipt.leaseId(),
                    binding.getId().toString()) != 1) throw conflict("HOSTING_RENT_CONFLICT");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { reconciler.wake(); } // Signal only; NO I/O or posting here.
            });
            return mutationView(quote.getAgentId(), receipt);
        });
    }

    public QuoteView renewalQuote(Actor actor, String leaseId, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor); key(idempotencyKey); exact(leaseId, 100);
        String agentId = required(body, "agentId"); requireCanonicalShape(agentId);
        long version = positive(required(body, "expectedLeaseVersion"));
        byte[] hash = hash("RENEWAL:" + leaseId, body);
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            EconomyHostingRentQuoteEntity replay = actorQuote(actor, idempotencyKey);
            if (replay != null) return quoteView(ledger.quote(quoteCommand(actor, idempotencyKey, hash,
                    replay.getPersonaCode(), agentId, replay.getPlanId(), replay.getPlanVersion(),
                    HostingRentQuotePurpose.RENEWAL, leaseId, version)));
            EconomyHostingRentPlanEntity plan = plan(actor);
            EconomyHostingLeaseEntity lease = ownedLease(actor, leaseId, agentId);
            requireExisting(actor, owner, agentId, lease.getPersonaCode());
            return quoteView(ledger.quote(quoteCommand(actor, idempotencyKey, hash, lease.getPersonaCode(), agentId,
                    plan.getPlanId(), plan.getPlanVersion(), HostingRentQuotePurpose.RENEWAL, leaseId, version)));
        });
    }

    public MutationView renew(Actor actor, String leaseId, String idempotencyKey, Map<String, String> body) {
        requireEnabled(actor); key(idempotencyKey); exact(leaseId, 100);
        byte[] hash = hash("RENEWAL-CONFIRM:" + leaseId, body);
        return transactions.execute(status -> {
            String owner = owners.requireOwner(actor);
            EconomyHostingRentQuoteEntity quote = ownedQuote(actor, required(body, "quoteId"));
            confirmation(quote, body, quote.getPersonaCode(), "RENEWAL");
            if (!leaseId.equals(quote.getLeaseId())
                    || !Objects.equals(quote.getExpectedLeaseVersion(), positive(required(body, "expectedLeaseVersion")))) throw badRequest();
            EconomyHostingProvisioningIntentEntity prior = rent.selectIntentByQuoteForUpdate(
                    actor.tenantId(), actor.clientId(), quote.getQuoteId());
            if (prior == null) {
                ownedLease(actor, leaseId, quote.getAgentId());
                requireExisting(actor, owner, quote.getAgentId(), quote.getPersonaCode());
            }
            return mutationView(quote.getAgentId(), ledger.renew(new HostingRentReserveCommand(
                    actor.scope(), actor.principal(), idempotencyKey, hash, quote.getQuoteId())));
        });
    }

    public LeaseView lookup(Actor actor, String agentId) {
        requireEnabled(actor); requireCanonicalShape(agentId);
        return reads.execute(status -> {
            String owner = owners.requireOwner(actor);
            var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, agentId);
            if (!agentId.equals(identity.getCanonicalAgentId())) throw forbidden();
            identities.requireActiveBinding(identity, null);
            EconomyHostingLeaseEntity latest = rent.selectLatestLease(actor.tenantId(), actor.clientId(), agentId);
            if (latest == null) return new LeaseView(false, null, null, "NOT_MANAGED");
            EconomyHostingLeaseEntity lease = latest;
            if (!actor.actorId().equals(lease.getPrincipalId()) || !"USER".equals(lease.getPrincipalType())) throw forbidden();
            EconomyHostingProvisioningIntentEntity intent = rent.selectIntent(
                    actor.tenantId(), actor.clientId(), lease.getLatestIntentId());
            if (intent == null || !actor.actorId().equals(intent.getPrincipalId())) throw forbidden();
            String admission = "ACTIVE".equals(lease.getStatus())
                    ? lease.getPaidThrough() > System.currentTimeMillis() ? "ALLOWED" : "RENEWAL_REQUIRED"
                    : "REFUNDED".equals(lease.getStatus()) ? "INITIAL_REQUIRED" : "PENDING";
            return new LeaseView(true, new LeaseSnapshot(lease.getLeaseId(), agentId, lease.getPersonaCode(),
                    lease.getBindingId(), number(lease.getVersion()), lease.getStatus(), number(lease.getPlanVersion()),
                    number(lease.getAmountMicro()), number(lease.getPeriodSeconds()), number(lease.getPaidFrom()), number(lease.getPaidThrough())),
                    new IntentSnapshot(intent.getIntentId(), number(intent.getVersion()), intent.getStatus(),
                            number(intent.getServiceReadyAt()), intent.getReserveTransactionId(), intent.getCaptureTransactionId(),
                            intent.getRefundTransactionId()), admission);
        });
    }

    private MutationView reprovision(Actor actor, String idempotencyKey, Map<String, String> body) {
        // Fail closed until an immutable free-reprovision request + trusted managed adapter exists.
        // Never turn this action into a renewal quote, reserve, or extension of paidThrough.
        if (!Set.of("mode", "hostingAction", "agentId", "leaseId", "expectedLeaseVersion")
                .containsAll(body.keySet())) throw badRequest();
        owners.requireOwner(actor); key(idempotencyKey);
        required(body, "agentId"); required(body, "leaseId"); positive(required(body, "expectedLeaseVersion"));
        throw new HostingRentApplicationException(503, "HOSTING_RENT_REPROVISION_NOT_READY");
    }

    private void requireEnabled(Actor actor) {
        if (!properties.configured()) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_CONFIGURED");
        if (!schemaEnabled) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_READY");
        preview.requireMutationAllowed(actor.tenantId(), actor.clientId());
    }

    private void requireProvisioner() {
        ManagedHostingProvisioner provider = provisioners.getIfAvailable();
        if (provider == null || !provider.available()) throw new HostingRentApplicationException(503, "HOSTING_RENT_NOT_READY");
    }

    private AgentPersonaEntity persona(String code) {
        AgentPersonaEntity persona = roots.lockPersona(code);
        if (persona == null || !code.equals(persona.getPersonaCode()) || !Boolean.TRUE.equals(persona.getActive())
                || Boolean.TRUE.equals(persona.getSystemAgent())) throw forbidden();
        return persona;
    }

    private AgentPersonaBindingEntity currentBinding(Actor actor, String owner, String personaCode) {
        var found = roots.lockBindings(actor.clientId(), personaCode);
        if (found.size() > 1) throw forbidden();
        if (found.isEmpty()) return null;
        AgentPersonaBindingEntity binding = found.getFirst();
        if (!owner.equals(binding.getJiacn()) || !actor.clientId().equals(binding.getClientId())
                || !personaCode.equals(binding.getPersonaCode())) throw forbidden();
        return binding;
    }

    private AgentIdentityRegistryEntity requireExisting(Actor actor, String owner, String agentId, String personaCode) {
        requireCanonicalShape(agentId);
        AgentIdentityRegistryEntity identity = identities.requireRegistrationIdentityInScope(
                actor.tenantId(), actor.clientId(), owner, agentId);
        if (!agentId.equals(identity.getCanonicalAgentId())) throw forbidden();
        AgentPersonaBindingEntity locked = bindings.findByIdForUpdate(identity.getBindingId());
        if (locked == null || !Integer.valueOf(AgentConstants.BINDING_STATUS_ACTIVE).equals(locked.getStatus())
                || !agentId.equals(locked.getAgentId()) || !actor.clientId().equals(locked.getClientId())
                || !owner.equals(locked.getJiacn())) throw forbidden();
        AgentPersonaBindingEntity binding = identities.requireActiveBinding(identity, null);
        if (personaCode != null && !personaCode.equals(binding.getPersonaCode())) throw forbidden();
        return identity;
    }

    private EconomyHostingRentQuoteEntity actorQuote(Actor actor, String key) {
        return rent.selectQuoteByActorKeyForUpdate(actor.tenantId(), actor.clientId(), "USER", actor.actorId(),
                key.getBytes(StandardCharsets.US_ASCII));
    }

    private EconomyHostingRentQuoteEntity ownedQuote(Actor actor, String quoteId) {
        EconomyHostingRentQuoteEntity quote = rent.selectQuoteForUpdate(actor.tenantId(), actor.clientId(), quoteId);
        if (quote == null || !actor.actorId().equals(quote.getPrincipalId()) || !"USER".equals(quote.getPrincipalType())) throw forbidden();
        return quote;
    }

    private EconomyHostingLeaseEntity ownedLease(Actor actor, String leaseId, String agentId) {
        EconomyHostingLeaseEntity lease = rent.selectLeaseForUpdate(actor.tenantId(), actor.clientId(), leaseId);
        if (lease == null || !actor.actorId().equals(lease.getPrincipalId()) || !agentId.equals(lease.getAgentId())
                || !"USER".equals(lease.getPrincipalType())) throw forbidden();
        return lease;
    }

    private EconomyHostingRentPlanEntity plan(Actor actor) {
        long version = positive(properties.planVersion());
        EconomyHostingRentPlanEntity plan = rent.selectPlanForUpdate(actor.tenantId(), actor.clientId(), PLAN_ID, version);
        if (plan == null) {
            EconomyHostingRentPlanEntity proposed = new EconomyHostingRentPlanEntity().setPlanId(PLAN_ID)
                    .setPlanVersion(version).setAmountMicro(positive(properties.amountMicro()))
                    .setPeriodSeconds(positive(properties.periodSeconds())).setQuoteTtlSeconds(300L)
                    .setCurrency("SILVER").setStatus("ACTIVE").setTenantId(actor.tenantId()).setClientId(actor.clientId())
                    .setCreateTime(System.currentTimeMillis());
            try { rent.insertPlanVersion(proposed); }
            catch (DataIntegrityViolationException concurrent) { /* Compare immutable winner below, never rewrite it. */ }
            plan = rent.selectPlanForUpdate(actor.tenantId(), actor.clientId(), PLAN_ID, version);
        }
        if (plan == null || plan.getAmountMicro() != positive(properties.amountMicro())
                || plan.getPeriodSeconds() != positive(properties.periodSeconds()) || !"ACTIVE".equals(plan.getStatus())) {
            throw conflict("HOSTING_RENT_PLAN_VERSION_CONFLICT");
        }
        return plan;
    }

    private void confirmation(EconomyHostingRentQuoteEntity quote, Map<String, String> body, String persona, String purpose) {
        if (!purpose.equals(quote.getQuotePurpose()) || !persona.equals(quote.getPersonaCode())
                || !quote.getAgentId().equals(required(body, "agentId"))
                || quote.getPlanVersion() != positive(required(body, "expectedPlanVersion"))
                || quote.getAmountMicro() != positive(required(body, "expectedAmountMicro"))
                || quote.getPeriodSeconds() != positive(required(body, "expectedPeriodSeconds"))) throw badRequest();
    }

    private HostingRentQuoteCommand quoteCommand(Actor actor, String key, byte[] hash, String persona, String agent,
            String plan, long version, HostingRentQuotePurpose purpose, String lease, Long leaseVersion) {
        return new HostingRentQuoteCommand(actor.scope(), actor.principal(), key, hash, purpose,
                plan, version, persona, agent, lease, leaseVersion);
    }
    private static void requireCanonicalShape(String agentId) {
        if (agentId == null || !agentId.matches("agt_[0-9a-f]{32}")) throw badRequest();
    }
    private static HostingRentApplicationException forbidden() { return new HostingRentApplicationException(404, "HOSTING_RENT_NOT_FOUND_OR_FORBIDDEN"); }
    private static HostingRentApplicationException conflict(String code) { return new HostingRentApplicationException(409, code); }
    private static String number(Long value) { return value == null ? null : value.toString(); }
    private static QuoteView quoteView(HostingRentQuoteReceipt quote) {
        return new QuoteView(quote.quoteId(), quote.purpose().name(), quote.personaCode(), quote.agentId(), quote.leaseId(),
                number(quote.expectedLeaseVersion()), quote.planId(), number(quote.planVersion()), "SILVER",
                number(quote.amountMicro()), number(quote.periodSeconds()), number(quote.expiresAt()));
    }
    private static MutationView mutationView(String agent, HostingRentMutationReceipt receipt) {
        return new MutationView(agent, receipt.intentId(), receipt.leaseId(), receipt.quoteId(), receipt.transactionId(),
                receipt.status(), "SILVER", number(receipt.amountMicro()), number(receipt.periodSeconds()), number(receipt.occurredAt()));
    }
    public record QuoteView(String quoteId, String purpose, String personaCode, String agentId, String leaseId,
                            String expectedLeaseVersion, String planId, String planVersion, String currency,
                            String amountMicro, String periodSeconds, String expiresAt) { }
    public record MutationView(String agentId, String intentId, String leaseId, String quoteId, String transactionId,
                               String status, String currency, String amountMicro, String periodSeconds, String occurredAt) { }
    public record LeaseView(boolean managed, LeaseSnapshot lease, IntentSnapshot intent, String admission) { }
    public record LeaseSnapshot(String leaseId, String agentId, String personaCode, String bindingId, String version,
                                String status, String planVersion, String amountMicro, String periodSeconds,
                                String paidFrom, String paidThrough) { }
    public record IntentSnapshot(String intentId, String version, String status, String serviceReadyAt,
                                 String reserveTransactionId, String captureTransactionId, String refundTransactionId) { }
}
