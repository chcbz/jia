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
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduledJobHandler;
import cn.jia.task.scheduling.ScheduledInvocation;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;


/** Narrow durable rent reconciliation, not a generic outbox. Every external step is outside a transaction. */
@Component
public final class HostingRentReconciler implements ScheduledJobHandler {
    public static final String HANDLER = "hosting-rent.provision";
    private final AgentHostingRentProperties properties;
    private final EconomyPreviewGate preview;
    private final EconomyHostingRentMapper mapper;
    private final HostingRentLedgerService ledger;
    private final AgentIdentityService identities;
    private final HostingRentOwnerResolver owners;
    private final ObjectProvider<ManagedHostingProvisioner> providers;
    private final ManagedHostingCredentials credentials;
    private final TransactionTemplate transactions;
    private final cn.jia.agent.dao.AgentPersonaBindingDao bindings;
    private final boolean schemaEnabled;
    private final cn.jia.core.security.FixedTenantAdmissionPolicy scope;
    private final ObjectProvider<ScheduleService> schedules;

    public HostingRentReconciler(AgentHostingRentProperties properties, EconomyPreviewGate preview,
            EconomyHostingRentMapper mapper, HostingRentLedgerService ledger, AgentIdentityService identities,
            HostingRentOwnerResolver owners, ObjectProvider<ManagedHostingProvisioner> providers,
            ManagedHostingCredentials credentials, PlatformTransactionManager transactionManager,
            cn.jia.agent.dao.AgentPersonaBindingDao bindings,
            cn.jia.core.security.FixedTenantAdmissionPolicy scope,
            @org.springframework.beans.factory.annotation.Value("${economy.hosting-rent.schema-enabled:false}") boolean schemaEnabled,
            ObjectProvider<ScheduleService> schedules) {
        this.properties = properties; this.preview = preview; this.mapper = mapper; this.ledger = ledger;
        this.identities = identities; this.owners = owners; this.providers = providers;
        this.credentials = credentials;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bindings = bindings; this.schemaEnabled = schemaEnabled; this.scope = scope; this.schedules = schedules;
    }

    @Override public String getName() { return HANDLER; }

    /** Joins the caller's reserve/reprovision transaction; Redis is only an after-commit hint. */
    public void wakeInitial(String clientId, String intentId) { signal(clientId, "initial:" + intentId); }
    public void wakeReprovision(String clientId, String requestId) { signal(clientId, "reprovision:" + requestId); }
    private void signal(String clientId, String key) {
        schedules.getObject().signal(new ScheduleKey(clientId, HANDLER, key, ""), 0);
    }

    /** One startup catch-up, not a periodic business-table poll. New work is registered atomically. */
    @EventListener(ApplicationReadyEvent.class)
    public void schedulePending() {
        if (!properties.configured() || !schemaEnabled) return;
        long cursor = 0;
        while (true) {
            var page = mapper.selectPendingIntents(scope.businessTenantId(), scope.clientId(), cursor);
            for (var row : page) wakeInitial(row.getClientId(), row.getIntentId());
            if (page.size() < 100) break;
            cursor = page.getLast().getId();
        }
        cursor = 0;
        while (true) {
            var page = mapper.selectPendingReprovisions(scope.businessTenantId(), scope.clientId(), cursor);
            for (var row : page) wakeReprovision(row.getClientId(), row.getRequestId());
            if (page.size() < 100) break;
            cursor = page.getLast().getId();
        }
    }

    @Override public void executeScheduled(ScheduledInvocation invocation) {
        scope.requireAdmitted(invocation.tenantId(), invocation.key().clientId());
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("rent I/O must be after commit");
        if (!properties.configured() || !schemaEnabled || !preview.allows(invocation.tenantId(), invocation.key().clientId()))
            throw new IllegalStateException("Hosting provisioning partition unavailable");
        String key = invocation.key().businessKey(), tenant = invocation.tenantId(), client = invocation.key().clientId();
        if (key.startsWith("initial:")) {
            String id = key.substring("initial:".length());
            var row = mapper.selectIntent(tenant, client, id);
            if (!pending(row)) return;
            reconcileOne(row, requireProvider());
            if (pending(mapper.selectIntent(tenant, client, id))) throw new IllegalStateException("Hosting initial outcome pending");
        } else if (key.startsWith("reprovision:")) {
            String id = key.substring("reprovision:".length());
            var row = transactions.execute(status -> mapper.selectReprovisionForUpdate(tenant, client, id));
            if (!pending(row)) return;
            reconcileFree(row, requireProvider());
            var current = transactions.execute(status -> mapper.selectReprovisionForUpdate(tenant, client, id));
            if (pending(current)) throw new IllegalStateException("Hosting reprovision outcome pending");
        } else throw new IllegalArgumentException("Unknown hosting schedule key");
    }
    private ManagedHostingProvisioner requireProvider() {
        var provider = providers.getIfAvailable();
        if (provider == null || !provider.available()) throw new IllegalStateException("Hosting provider unavailable");
        return provider;
    }
    private static boolean pending(EconomyHostingProvisioningIntentEntity row) {
        return row != null && java.util.Set.of("FUNDS_RESERVED", "PROVISIONING_UNKNOWN", "SERVICE_READY", "FAILED_NO_EFFECT").contains(row.getStatus());
    }
    private static boolean pending(EconomyHostingReprovisionEntity row) {
        return row != null && java.util.Set.of("ACCEPTED", "PROVISIONING_UNKNOWN").contains(row.getStatus());
    }

    void reconcileOne(EconomyHostingProvisioningIntentEntity row, ManagedHostingProvisioner provider) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("rent I/O transaction boundary");
        Snapshot snapshot = transactions.execute(status -> snapshot(row));
        if (snapshot == null) return;
        if ("SERVICE_READY".equals(snapshot.status())) {
            // Historical READY is not current registration evidence after a crash/restart.
            // Adapter revalidates exact persisted Runtime-v1 linkage and live API proof.
            var resumed = provider.prepareAndObserve(snapshot.preparation());
            if (resumed != null && snapshot.preparation().equals(resumed.preparation())
                    && resumed.outcome() == ManagedHostingProvisioner.Outcome.SERVICE_READY) {
                HostingRentHttp.exact(resumed.evidenceRef(), 100);
                settle(snapshot, true); // Preserve recorded readyAt/period and outcome version.
            }
            return;
        }
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
            settle(new Snapshot(snapshot.actor(), snapshot.preparation(), "SERVICE_READY", version + 1, snapshot.intent()), true);
        } else {
            ledger.confirmProvisioningFailedNoEffect(command);
            settle(new Snapshot(snapshot.actor(), snapshot.preparation(), "FAILED_NO_EFFECT", version + 1, snapshot.intent()), false);
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
        var binding = bindings.findActiveByTenantClientAndPersonaForUpdate(
                row.getTenantId(), row.getClientId(), row.getPersonaCode());
        if (binding == null || !Integer.valueOf(1).equals(binding.getStatus())
                || !row.getClientId().equals(binding.getClientId()) || !row.getAgentId().equals(binding.getAgentId())) return null;
        var initial = mapper.selectIntentForUpdate(row.getTenantId(), row.getClientId(), row.getIntentId());
        if (initial == null || !"INITIAL".equals(initial.getQuotePurpose()) || !"ACTIVE".equals(initial.getStatus())
                || !"USER".equals(initial.getPrincipalType()) || !row.getPrincipalId().equals(initial.getPrincipalId())
                || !row.getLeaseId().equals(initial.getLeaseId()) || !row.getAgentId().equals(initial.getAgentId())) return null;
        var lease = mapper.selectLeaseForUpdate(row.getTenantId(), row.getClientId(), row.getLeaseId());
        if (lease == null || !"ACTIVE".equals(lease.getStatus()) || !"USER".equals(lease.getPrincipalType())
                || !initial.getPrincipalId().equals(lease.getPrincipalId()) || !row.getAgentId().equals(lease.getAgentId())
                || lease.getBindingId() == null || !row.getPersonaCode().equals(lease.getPersonaCode())) return null;
        if (!lease.getBindingId().equals(binding.getId().toString())) return null;
        String owner = binding.getJiacn();
        if (owner == null || owner.isBlank()) return null;
        var actor = new HostingRentHttp.Actor(
                initial.getPrincipalId(), row.getTenantId(), row.getClientId(), owner);
        if (!owner.equals(owners.requireOwner(actor))) return null;
        var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, row.getAgentId());
        if (!row.getAgentId().equals(identity.getCanonicalAgentId()) || !lease.getBindingId().equals(String.valueOf(identity.getBindingId()))) return null;
        var request = mapper.selectReprovisionForUpdate(actor.tenantId(), actor.clientId(), row.getRequestId());
        if (request == null || !row.getIntentId().equals(request.getIntentId()) || !row.getLeaseId().equals(request.getLeaseId())
                || !row.getAgentId().equals(request.getAgentId()) || !actor.actorId().equals(request.getPrincipalId())
                || !("ACCEPTED".equals(request.getStatus()) || "PROVISIONING_UNKNOWN".equals(request.getStatus()))) return null;
        // Authorization fixes a durable target before Runtime ensure. Recheck it after
        // external I/O so an old registered operation cannot finish a different target.
        if (!markUnknown && (request.getRuntimeTargetGeneration() == null || request.getRuntimeTargetGeneration() <= 1
                || !request.getRuntimeTargetGeneration().equals(initial.getRuntimeProvisionGeneration())
                || initial.getRuntimeInstallationId() == null || initial.getRuntimeManifestSha256() == null)) return null;
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
        // Discovery is only a hint; lock the scoped binding before intent/lease and recheck linkage.
        var lockedBinding = bindings.findActiveByTenantClientAndPersonaForUpdate(
                row.getTenantId(), row.getClientId(), row.getPersonaCode());
        if (lockedBinding == null || !Integer.valueOf(1).equals(lockedBinding.getStatus())
                || !row.getAgentId().equals(lockedBinding.getAgentId())
                || !row.getClientId().equals(lockedBinding.getClientId())) return null;
        var intent = mapper.selectIntentForUpdate(row.getTenantId(), row.getClientId(), row.getIntentId());
        if (intent == null || !"USER".equals(intent.getPrincipalType()) || !"INITIAL".equals(intent.getQuotePurpose())
                || "ACTIVE".equals(intent.getStatus()) || "REFUNDED".equals(intent.getStatus())) return null;
        var lease = mapper.selectLeaseForUpdate(intent.getTenantId(), intent.getClientId(), intent.getLeaseId());
        if (lease == null || !intent.getIntentId().equals(lease.getLatestIntentId())
                || !intent.getAgentId().equals(lease.getAgentId()) || !intent.getPrincipalId().equals(lease.getPrincipalId())
                || !"USER".equals(lease.getPrincipalType())
                || !"PROVISIONING".equals(lease.getStatus()) || lease.getBindingId() == null) return null;
        if (!lease.getBindingId().equals(lockedBinding.getId().toString())
                || !intent.getAgentId().equals(lockedBinding.getAgentId())
                || !intent.getPersonaCode().equals(lockedBinding.getPersonaCode())) return null;
        String owner = lockedBinding.getJiacn();
        if (owner == null || owner.isBlank()) return null;
        var actor = new HostingRentHttp.Actor(
                intent.getPrincipalId(), intent.getTenantId(), intent.getClientId(), owner);
        if (!owner.equals(owners.requireOwner(actor))) return null;
        var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, intent.getAgentId());
        if (!intent.getAgentId().equals(identity.getCanonicalAgentId())
                || !lockedBinding.getId().equals(identity.getBindingId())) return null;
        var quote = mapper.selectQuoteInventory(actor.tenantId(), actor.clientId(), intent.getQuoteId());
        if (quote == null || quote.getInventoryHostId() == null || quote.getInventorySha256() == null) return null;
        var preparation = new ManagedHostingProvisioner.Preparation(actor.tenantId(), actor.clientId(), owner,
                intent.getAgentId(), intent.getIntentId(), intent.getLeaseId(), lease.getBindingId(), intent.getReservedAt())
                .withQuotedInventory(new ManagedHostingProvisioner.QuotedInventory(quote.getInventoryHostId(), quote.getInventorySha256()));
        return new Snapshot(actor, preparation, intent.getStatus(), intent.getVersion(), intent);
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
            // Use the discovery keys only, then lock/revalidate binding -> intent -> lease after I/O.
            Snapshot checked = snapshot(snapshot.intent());
            if (checked == null || !checked.preparation().equals(snapshot.preparation())) return;
            var current = checked.intent();
            if (capture) {
                if (!"SERVICE_READY".equals(current.getStatus()) || current.getRuntimeInstallationId() == null
                        || current.getRuntimeManifestSha256() == null
                        || !Long.valueOf(1).equals(current.getRuntimeProvisionGeneration())) return;
                ledger.capture(command);
            } else {
                // Posting and credential disposition share this REQUIRED transaction: either
                // no credential was issued, or the exact historical key is revoked.
                ledger.refund(command);
                credentials.disableForRefund(checked.preparation(), current);
            }
        });
    }
    private record Snapshot(HostingRentHttp.Actor actor, ManagedHostingProvisioner.Preparation preparation,
                            String status, long version, EconomyHostingProvisioningIntentEntity intent) { }
}
