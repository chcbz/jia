package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.exception.AgentTaskArtifactStorageException;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveTransactions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Bounded source-object recovery driven only by durable feature-owned rows, never directory scans. */
@Component
public final class ArchiveSourceArtifactReconciler {
    private final ArchiveMaintenanceStore store;
    private final ArchiveTransactions transactions;
    private final AgentTaskArtifactStorage storage;
    private final ArchiveMaintenanceProperties properties;
    private final Clock clock;
    private String cursor;

    @Autowired
    public ArchiveSourceArtifactReconciler(ArchiveMaintenanceStore store, ArchiveTransactions transactions,
            AgentTaskArtifactStorage storage, ArchiveMaintenanceProperties properties) {
        this(store, transactions, storage, properties, Clock.systemUTC());
    }

    ArchiveSourceArtifactReconciler(ArchiveMaintenanceStore store, ArchiveTransactions transactions,
            AgentTaskArtifactStorage storage, ArchiveMaintenanceProperties properties, Clock clock) {
        this.store=Objects.requireNonNull(store); this.transactions=Objects.requireNonNull(transactions);
        this.storage=Objects.requireNonNull(storage); this.properties=Objects.requireNonNull(properties);
        this.clock=Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString="${archive.maintenance.source-cleanup-delay-ms:60000}")
    public synchronized void reconcile() {
        if (!properties.isSourceCleanupEnabled()) return;
        long staleMillis=properties.getSourceCleanupStaleMillis();
        int limit=properties.getSourceCleanupBatchSize();
        if (staleMillis <= 0 || limit <= 0) {
            throw new IllegalStateException("Archive source cleanup configuration is invalid");
        }
        Instant before=clock.instant().minusMillis(staleMillis);
        var page=store.listStaleSourceArtifacts(before,cursor,limit);
        if (page.isEmpty()) { cursor=null; return; }
        for (var candidate:page) {
            cursor=candidate.sourceId();
            try { reconcileOne(candidate,before); }
            catch (RuntimeException deferred) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                        "Archive source artifact cleanup deferred for {}", candidate.sourceId());
            }
        }
        if (page.size()<limit) cursor=null;
    }

    private void reconcileOne(ArchiveMaintenanceStore.SourceArtifact candidate, Instant before) {
        ArchiveMaintenanceStore.SourceArtifact claimed=transactions.required(() -> {
            // Same operation -> generation lock order as prepareSource. A previous
            // generation remains collectible after the operation commits a newer one.
            var operation=store.lockSourceArtifactOperation(candidate);
            if (operation==null || !"SOURCE".equals(operation.targetType())) return null;
            var current=store.findSourceArtifact(candidate.sourceId(),true);
            if (current==null) return null;
            if ("PENDING".equals(current.state()) || "DELETED".equals(current.state())) {
                if (store.claimSourceArtifactDeletion(current.sourceId(),current.revision(),before)!=1) return null;
                current=store.findSourceArtifact(current.sourceId(),true);
            }
            return "DELETE_PENDING".equals(current.state()) ? current : null;
        });
        if (claimed==null) return;
        AgentTaskArtifactStorage.Scope scope=new AgentTaskArtifactStorage.Scope(claimed.tenantId(),
                claimed.clientId(),claimed.ownerJiacn(),claimed.sourceId());
        if (!storage.matches(scope,claimed.storageUri(),claimed.sha256())) {
            throw new AgentTaskArtifactStorageException(
                    AgentTaskArtifactStorageException.Reason.CORRUPT_CONTENT,
                    "Tracked source object is outside its exact namespace");
        }
        storage.delete(scope,claimed.storageUri(),claimed.sha256());
        transactions.required(() -> {
            var current=store.findSourceArtifact(claimed.sourceId(),true);
            if (current!=null && "DELETE_PENDING".equals(current.state())
                    && current.revision()==claimed.revision()
                    && store.completeSourceArtifactDeletion(current.sourceId(),current.revision())!=1) {
                throw new IllegalStateException("Archive source artifact cleanup completion raced");
            }
            return null;
        });
    }
}
