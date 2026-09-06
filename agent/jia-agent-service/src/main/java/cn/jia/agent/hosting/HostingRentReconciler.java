package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.entity.EconomyHostingReprovisionEntity;
import cn.jia.economy.hosting.HostingRentLedgerService;
import cn.jia.economy.hosting.HostingRentOutcomeCommand;
import cn.jia.economy.hosting.HostingRentSettlementCommand;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Narrow durable rent reconciliation, not a generic outbox. Every external step is outside a transaction. */
@Component
public final class HostingRentReconciler {
    private final AgentHostingRentProperties properties;
    private final EconomyPreviewGate preview;
    private final EconomyHostingRentMapper mapper;
    private final HostingRentLedgerService ledger;
    private final AgentIdentityService identities;
    private final HostingRentOwnerResolver owners;
    private final ObjectProvider<ManagedHostingProvisioner> providers;
    private final TransactionTemplate transactions;
    private final cn.jia.agent.dao.AgentPersonaBindingDao bindings;
    private final boolean schemaEnabled;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HostingRentReconciler.class);
    private final AtomicBoolean wake = new AtomicBoolean();
    private long freeScanAfterId;
    private long scanAfterId; // Fair bounded pages; resets on restart, never replaces durable intent state.

    public HostingRentReconciler(AgentHostingRentProperties properties, EconomyPreviewGate preview,
            EconomyHostingRentMapper mapper, HostingRentLedgerService ledger, AgentIdentityService identities,
            HostingRentOwnerResolver owners, ObjectProvider<ManagedHostingProvisioner> providers,
            PlatformTransactionManager transactionManager, cn.jia.agent.dao.AgentPersonaBindingDao bindings,
            @org.springframework.beans.factory.annotation.Value("${economy.hosting-rent.schema-enabled:false}") boolean schemaEnabled) {
        this.properties = properties; this.preview = preview; this.mapper = mapper; this.ledger = ledger;
        this.identities = identities; this.owners = owners; this.providers = providers;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bindings = bindings; this.schemaEnabled = schemaEnabled;
    }

    public void wake() { wake.set(true); }

    @Scheduled(fixedDelayString = "${agent.hosting-rent.reconcile-delay-ms:5000}")
    public void reconcilePending() {
        if (!properties.configured() || !schemaEnabled) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("rent I/O must be after commit");
        ManagedHostingProvisioner provider = providers.getIfAvailable();
        if (provider == null || !provider.available()) return;
        wake.getAndSet(false);
        // Poll even without a wake: the persisted intent, NOT the in-memory signal, survives crashes.
        var page = mapper.selectPendingIntents(scanAfterId);
        scanAfterId = page.size() < 100 ? 0L : page.getLast().getId();
        for (EconomyHostingProvisioningIntentEntity row : page) {
            if (!preview.allows(row.getTenantId(), row.getClientId())) continue;
            try { reconcileOne(row, provider); }
            catch (RuntimeException unknown) {
                // Never infer no-effect from exception/timeout/rollback. Persisted escrow stays held.
                // No filesystem compensation or exception-as-failure settlement here.
                LOG.warn("Hosting rent reconciliation deferred for intent {} ({})",
                        row.getIntentId(), unknown.getClass().getSimpleName());
            }
        }
        var freePage = mapper.selectPendingReprovisions(freeScanAfterId);
        freeScanAfterId = freePage.size() < 100 ? 0L : freePage.getLast().getId();
        for (var row : freePage) {
            if (!preview.allows(row.getTenantId(), row.getClientId())) continue;
            try { reconcileFree(row, provider); }
            catch (RuntimeException unknown) {
                LOG.warn("Free hosting reconciliation deferred for request {} ({})", row.getRequestId(), unknown.getClass().getSimpleName());
            }
        }
    }

    void reconcileOne(EconomyHostingProvisioningIntentEntity row, ManagedHostingProvisioner provider) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("rent I/O transaction boundary");
        Snapshot snapshot = transactions.execute(status -> snapshot(row));
        if (snapshot == null) return;
        if ("SERVICE_READY".equals(snapshot.status())) { settle(snapshot, true); return; }
        if ("FAILED_NO_EFFECT".equals(snapshot.status())) { settle(snapshot, false); return; }
        long version = snapshot.version();
        if ("FUNDS_RESERVED".equals(snapshot.status())) {
            ledger.markProvisioningUnknown(outcome(snapshot, version, "managed-attempt:" + snapshot.preparation().intentId()));
            version = Math.addExact(version, 1);
        }
        // Adapter must be idempotent and prove exact intent + Agent readiness; no public outcome is accepted.
        ManagedHostingProvisioner.Observation observation = provider.prepareAndObserve(snapshot.preparation());
        if (observation == null || !snapshot.preparation().equals(observation.preparation())
                || observation.outcome() == null || observation.outcome() == ManagedHostingProvisioner.Outcome.UNKNOWN) return;
        HostingRentHttp.exact(observation.evidenceRef(), 100);
        HostingRentOutcomeCommand command = new HostingRentOutcomeCommand(snapshot.actor().scope(), snapshot.actor().principal(),
                snapshot.preparation().intentId(), version, observation.evidenceRef(), observation.serviceReadyAt());
        if (observation.outcome() == ManagedHostingProvisioner.Outcome.SERVICE_READY) {
            ledger.confirmProvisioningSucceeded(command);
            settle(new Snapshot(snapshot.actor(), snapshot.preparation(), "SERVICE_READY", version + 1), true);
        } else {
            ledger.confirmProvisioningFailedNoEffect(command);
            settle(new Snapshot(snapshot.actor(), snapshot.preparation(), "FAILED_NO_EFFECT", version + 1), false);
        }
    }

    void reconcileFree(EconomyHostingReprovisionEntity row, ManagedHostingProvisioner provider) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("rent I/O transaction boundary");
        FreeSnapshot snapshot = transactions.execute(status -> freeSnapshot(row, true));
        if (snapshot == null) return;
        var observation = provider.prepareAndObserve(snapshot.preparation());
        if (observation == null || !snapshot.preparation().equals(observation.preparation())
                || observation.outcome() == null || observation.outcome() == ManagedHostingProvisioner.Outcome.UNKNOWN) return;
        HostingRentHttp.exact(observation.evidenceRef(), 100);
        Long readyAt = observation.serviceReadyAt();
        if (observation.outcome() == ManagedHostingProvisioner.Outcome.SERVICE_READY
                && (readyAt == null || readyAt < snapshot.preparation().requestedAt()
                    || readyAt >= snapshot.preparation().validUntil() || readyAt > System.currentTimeMillis())) return;
        transactions.executeWithoutResult(status -> {
            FreeSnapshot current = freeSnapshot(row, false);
            if (current == null || !current.preparation().equals(snapshot.preparation()) || current.version() != snapshot.version()) return;
            String outcome = observation.outcome().name();
            if (mapper.finishReprovision(row.getTenantId(), row.getClientId(), row.getRequestId(), current.version(),
                    outcome, "SERVICE_READY".equals(outcome) ? readyAt : null, observation.evidenceRef()) != 1) {
                throw new IllegalStateException("Free reprovision outcome CAS");
            }
            // No capture, reserve, refund or paid-period update exists on this path.
        });
    }

    private FreeSnapshot freeSnapshot(EconomyHostingReprovisionEntity row, boolean markUnknown) {
        var initial = mapper.selectIntentForUpdate(row.getTenantId(), row.getClientId(), row.getIntentId());
        if (initial == null || !"INITIAL".equals(initial.getQuotePurpose()) || !"ACTIVE".equals(initial.getStatus())
                || !"USER".equals(initial.getPrincipalType()) || !row.getPrincipalId().equals(initial.getPrincipalId())
                || !row.getLeaseId().equals(initial.getLeaseId()) || !row.getAgentId().equals(initial.getAgentId())) return null;
        var actor = new HostingRentHttp.Actor(initial.getPrincipalId(), row.getTenantId(), row.getClientId());
        String owner = owners.requireOwner(actor);
        var lease = mapper.selectLeaseForUpdate(actor.tenantId(), actor.clientId(), row.getLeaseId());
        if (lease == null || !"ACTIVE".equals(lease.getStatus()) || !"USER".equals(lease.getPrincipalType())
                || !actor.actorId().equals(lease.getPrincipalId()) || !row.getAgentId().equals(lease.getAgentId())
                || lease.getBindingId() == null || !row.getPersonaCode().equals(lease.getPersonaCode())) return null;
        var binding = bindings.findByIdForUpdate(Long.parseLong(lease.getBindingId()));
        if (binding == null || !Integer.valueOf(1).equals(binding.getStatus()) || !owner.equals(binding.getJiacn())
                || !actor.clientId().equals(binding.getClientId()) || !row.getAgentId().equals(binding.getAgentId())) return null;
        var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, row.getAgentId());
        var canonicalBinding = identities.requireActiveBinding(identity, null);
        if (!row.getAgentId().equals(identity.getCanonicalAgentId()) || !lease.getBindingId().equals(canonicalBinding.getId().toString())) return null;
        var request = mapper.selectReprovisionForUpdate(actor.tenantId(), actor.clientId(), row.getRequestId());
        if (request == null || !row.getIntentId().equals(request.getIntentId()) || !row.getLeaseId().equals(request.getLeaseId())
                || !row.getAgentId().equals(request.getAgentId()) || !actor.actorId().equals(request.getPrincipalId())
                || !("ACCEPTED".equals(request.getStatus()) || "PROVISIONING_UNKNOWN".equals(request.getStatus()))) return null;
        long version = request.getVersion();
        if ("ACCEPTED".equals(request.getStatus())) {
            if (!markUnknown) return null;
            if (mapper.markReprovisionUnknown(actor.tenantId(), actor.clientId(), request.getRequestId(), version) != 1) return null;
            version = Math.addExact(version, 1);
        }
        return new FreeSnapshot(new ManagedHostingProvisioner.Preparation(actor.tenantId(), actor.clientId(), owner,
                row.getAgentId(), initial.getIntentId(), lease.getLeaseId(), lease.getBindingId(), initial.getReservedAt(),
                request.getRequestId(), request.getRequestedAt(), request.getPaidThrough()), version);
    }

    private record FreeSnapshot(ManagedHostingProvisioner.Preparation preparation, long version) { }

    private Snapshot snapshot(EconomyHostingProvisioningIntentEntity row) {
        var intent = mapper.selectIntentForUpdate(row.getTenantId(), row.getClientId(), row.getIntentId());
        if (intent == null || !"USER".equals(intent.getPrincipalType()) || !"INITIAL".equals(intent.getQuotePurpose())
                || "ACTIVE".equals(intent.getStatus()) || "REFUNDED".equals(intent.getStatus())) return null;
        var actor = new HostingRentHttp.Actor(intent.getPrincipalId(), intent.getTenantId(), intent.getClientId());
        String owner = owners.requireOwner(actor);
        var lease = mapper.selectLeaseForUpdate(actor.tenantId(), actor.clientId(), intent.getLeaseId());
        if (lease == null || !intent.getIntentId().equals(lease.getLatestIntentId())
                || !intent.getAgentId().equals(lease.getAgentId()) || !actor.actorId().equals(lease.getPrincipalId())
                || !"USER".equals(lease.getPrincipalType())
                || !"PROVISIONING".equals(lease.getStatus()) || lease.getBindingId() == null) return null;
        var lockedBinding = bindings.findByIdForUpdate(Long.parseLong(lease.getBindingId()));
        if (lockedBinding == null || !Integer.valueOf(1).equals(lockedBinding.getStatus())
                || !intent.getAgentId().equals(lockedBinding.getAgentId())
                || !owner.equals(lockedBinding.getJiacn()) || !actor.clientId().equals(lockedBinding.getClientId())) return null;
        var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, intent.getAgentId());
        var binding = identities.requireActiveBinding(identity, null);
        if (!intent.getAgentId().equals(identity.getCanonicalAgentId())
                || !lease.getBindingId().equals(binding.getId().toString())
                || !intent.getPersonaCode().equals(binding.getPersonaCode())) return null;
        var preparation = new ManagedHostingProvisioner.Preparation(actor.tenantId(), actor.clientId(), owner,
                intent.getAgentId(), intent.getIntentId(), intent.getLeaseId(), lease.getBindingId(), intent.getReservedAt());
        return new Snapshot(actor, preparation, intent.getStatus(), intent.getVersion());
    }

    private HostingRentOutcomeCommand outcome(Snapshot snapshot, long version, String proof) {
        return new HostingRentOutcomeCommand(snapshot.actor().scope(), snapshot.actor().principal(),
                snapshot.preparation().intentId(), version, proof);
    }

    private void settle(Snapshot snapshot, boolean capture) {
        String action = capture ? "capture" : "refund";
        String intentId = snapshot.preparation().intentId();
        String key = UUID.nameUUIDFromBytes(("managed-hosting:" + action + ":" + intentId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        var command = new HostingRentSettlementCommand(snapshot.actor().scope(), snapshot.actor().principal(), key,
                HostingRentHttp.hash(action, Map.of("intentId", intentId)), intentId, snapshot.version());
        transactions.executeWithoutResult(status -> {
            var current = mapper.selectIntentForUpdate(snapshot.actor().tenantId(), snapshot.actor().clientId(), intentId);
            if (current == null) return;
            // Lock/revalidate the same canonical binding again after external observation, before posting.
            Snapshot checked = snapshot(current);
            if (checked == null || !checked.preparation().equals(snapshot.preparation())) return;
            if (capture) ledger.capture(command); else ledger.refund(command);
        });
    }
    private record Snapshot(HostingRentHttp.Actor actor, ManagedHostingProvisioner.Preparation preparation,
                            String status, long version) { }
}
