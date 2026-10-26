package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.Outcome;
import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import org.springframework.dao.TransientDataAccessException;

/**
 * Claims task-transaction intents without a browser session. Acknowledges only the committed
 * Chat request/step; neither claim nor ADMITTED means Agent execution or a finished deliverable.
 * Disabled until the exact schema and product routing are ready to enable together.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "chat.bounty-bootstrap", name = "enabled", havingValue = "true")
public class ChatBountyBootstrapRelay implements SmartLifecycle, AutoCloseable {
    private final AgentTaskBountyBootstrapOutboxService outbox;
    private final ChatBountyBootstrapAdmissionService admission;
    private final ChatTypedDeliberationContextService contexts;
    private final Supplier<ScheduledExecutorService> schedulerFactory;
    // Reuse the outbox recovery backoff range: delay claim attempts, never expire user work.
    static final long CLAIM_RETRY_BASE_MILLIS = 1_000L;
    static final long CLAIM_RETRY_MAX_MILLIS = 300_000L;
    private final LongSupplier clock;
    private volatile long claimRetryNotBefore;
    private volatile long claimRetryDelay = CLAIM_RETRY_BASE_MILLIS;
    private final String consumerId = "mmd-chat-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile String previousFailure;
    private volatile int sameFailureCount;

    @Autowired
    public ChatBountyBootstrapRelay(AgentTaskBountyBootstrapOutboxService outbox,
            ChatBountyBootstrapAdmissionService admission, ChatTypedDeliberationContextService contexts) {
        this(outbox, admission, contexts, () -> Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-bounty-bootstrap");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ChatBountyBootstrapRelay(AgentTaskBountyBootstrapOutboxService outbox,
            ChatBountyBootstrapAdmissionService admission, ChatTypedDeliberationContextService contexts,
            Supplier<ScheduledExecutorService> schedulerFactory) {
        this(outbox, admission, contexts, schedulerFactory, System::currentTimeMillis);
    }

    ChatBountyBootstrapRelay(AgentTaskBountyBootstrapOutboxService outbox,
            ChatBountyBootstrapAdmissionService admission, ChatTypedDeliberationContextService contexts,
            Supplier<ScheduledExecutorService> schedulerFactory, LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        this.outbox = Objects.requireNonNull(outbox);
        this.admission = Objects.requireNonNull(admission);
        this.contexts = Objects.requireNonNull(contexts);
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory);
    }

    @Override public synchronized void start() {
        if (contexts.bootstrapReadiness() == Readiness.DISABLED
                || !running.compareAndSet(false, true)) return;
        previousFailure = null;
        sameFailureCount = 0;
        claimRetryNotBefore = 0;
        claimRetryDelay = CLAIM_RETRY_BASE_MILLIS;
        try {
            ScheduledExecutorService active = Objects.requireNonNull(schedulerFactory.get());
            scheduler = active;
            active.scheduleWithFixedDelay(() -> safePoll(active), 0, 500, TimeUnit.MILLISECONDS);
        } catch (RuntimeException | Error failure) {
            stop();
            throw failure;
        }
    }

    /** One persistent claim per poll, so a retry never spins on the same row in one loop. */
    void pollOnce() {
        if (!polling.compareAndSet(false, true)) return;
        try { pollWhenReady(); }
        finally { polling.set(false); }
    }

    private boolean pollWhenReady() {
        Readiness readiness = contexts.bootstrapReadiness();
        if (readiness == Readiness.INITIALIZING) return false;
        if (readiness == Readiness.DISABLED) {
            stop();
            return false;
        }
        if (readiness != Readiness.READY) {
            throw new IllegalStateException("Typed deliberation schema is unavailable");
        }
        // SmartLifecycle starts before ApplicationRunner: do not acquire a lease or consume
        // an attempt until typed admission is ready. Terminal unavailability is a real failure.
        long now = clock.getAsLong();
        if (now < claimRetryNotBefore) return false;
        AgentTaskBountyBootstrapClaimDTO claim;
        try {
            claim = outbox.claimNextAvailable(consumerId, now);
        } catch (RuntimeException failure) {
            // Only retry acquisition of a durable fenced intent. No Chat/Provider call has
            // happened here. An uncertain claim remains protected by its existing lease.
            if (!transientClaimFailure(failure)) throw failure;
            claimRetryNotBefore = clock.getAsLong() + claimRetryDelay;
            log.warn("Bounty bootstrap claim temporarily unavailable; retrying durable claim in {} ms",
                    claimRetryDelay, failure);
            claimRetryDelay = Math.min(CLAIM_RETRY_MAX_MILLIS, claimRetryDelay * 2);
            return false;
        }
        claimRetryNotBefore = 0;
        claimRetryDelay = CLAIM_RETRY_BASE_MILLIS;
        if (claim == null) return true;
        AgentTaskExecutionGrantService.Scope scope = new AgentTaskExecutionGrantService.Scope(
                claim.tenantId(), claim.clientId(), claim.ownerJiacn());
        ChatBountyBootstrapAdmissionService.Admission result;
        try {
            admission.prepare(claim); // immutable package I/O before the transactional admission
            result = admission.admit(claim); // transactional commit before the acknowledgement below
        } catch (ChatTypedDeliberationContextService.RuntimeNotReadyException awaiting) {
            // Admission has rolled back. Release this exact lease through the existing durable
            // retry/backoff, allowing other targets to progress without replaying the demand.
            // This wait is neither success nor an unchanged permanent failure.
            reconcile(scope, claim, Outcome.RETRYABLE_FAILURE, null, null,
                    "BOUNTY_RUNTIME_AWAITING");
            return false;
        } catch (RuntimeException failure) {
            // A missing owner-scoped revision or a revoked grant must never be replaced by a
            // truncated task-plan projection or a text CHAT dispatch. Retain for reconciliation.
            log.warn("Bounty bootstrap admission failed, will retry with original intent", failure);
            reconcile(scope, claim, Outcome.RETRYABLE_FAILURE, null, null,
                    "BOUNTY_ADMISSION_UNAVAILABLE");
            throw new IllegalStateException("BOUNTY_ADMISSION_UNAVAILABLE", failure);
        }
        reconcile(scope, claim, Outcome.ADMITTED, result.conversationId(), result.requestId(), null);
        return true;
    }

    private static boolean transientClaimFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException) return true;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40001"))) return true;
            }
        }
        // Schema, authorization, protocol and unknown evidence failures retain fail-closed handling.
        return false;
    }

    private void reconcile(AgentTaskExecutionGrantService.Scope scope,
            AgentTaskBountyBootstrapClaimDTO claim, Outcome outcome, String conversationId,
            String requestId, String errorCode) {
        var response = outbox.reconcile(scope, new AgentTaskBountyBootstrapReconcileDTO(
                claim.bootstrapId(), claim.outboxVersion(), claim.leaseOwner(), claim.claimAttempt(),
                outcome, conversationId, requestId, errorCode), System.currentTimeMillis());
        if (response == null || !claim.bootstrapId().equals(response.bootstrapId())
                || outcome == Outcome.RETRYABLE_FAILURE && (!"RETRY".equals(response.status())
                || response.conversationId() != null || response.initialRequestId() != null)
                || outcome == Outcome.ADMITTED && (!"ADMITTED".equals(response.status())
                || !Objects.equals(conversationId, response.conversationId())
                || !Objects.equals(requestId, response.initialRequestId()))) {
            throw new IllegalStateException("Bounty bootstrap reconciliation was not durable");
        }
    }

    private void safePoll(ScheduledExecutorService owner) {
        if (!running.get() || scheduler != owner || !polling.compareAndSet(false, true)) return;
        try {
            if (!running.get() || scheduler != owner) return;
            if (pollWhenReady()) {
                synchronized (this) {
                    if (scheduler != owner) return;
                    previousFailure = null;
                    sameFailureCount = 0;
                }
            }
        } catch (RuntimeException failure) {
            // Never silently busy-loop an unchanged DB/schema/fence failure. The claim lease
            // remains authoritative and can be safely reclaimed after an operator fixes it.
            synchronized (this) {
                if (scheduler != owner) return; // an old lifecycle cannot stop its replacement
                String signature = failure.getClass().getName() + ":" + failure.getMessage();
                sameFailureCount = Objects.equals(signature, previousFailure) ? sameFailureCount + 1 : 1;
                previousFailure = signature;
                log.warn("Bounty bootstrap relay cannot progress", failure);
                if (sameFailureCount >= 2) {
                    log.error("Bounty bootstrap relay stopped after repeated unchanged failure");
                    stop();
                }
            }
        } finally {
            polling.set(false);
        }
    }

    @Override public synchronized void stop() {
        running.set(false);
        ScheduledExecutorService active = scheduler;
        scheduler = null;
        if (active != null) active.shutdown();
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public void close() { stop(); }
}
