package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapWakeup;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.Outcome;
import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.dao.TransientDataAccessException;

/**
 * Claims task-transaction intents without a browser session. Acknowledges only the committed
 * Chat request/step; neither claim nor ADMITTED means Agent execution or a finished deliverable.
 * Disabled until the exact schema and product routing are ready to enable together.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = {"chat.bounty-bootstrap.enabled", "cyf.task.scheduling.enabled"}, havingValue = "true")
public class ChatBountyBootstrapRelay implements SmartLifecycle, AutoCloseable, ScheduledJobHandler {
    public static final String HANDLER = "chat.bounty-bootstrap";
    private final AgentTaskBountyBootstrapOutboxService outbox;
    private final ChatBountyBootstrapAdmissionService admission;
    private final ChatTypedDeliberationContextService contexts;
    private final ScheduleService schedules;
    private final ScheduleKey scheduleKey;
    private final TransactionTemplate notificationTransaction;
    // Reuse the outbox recovery backoff range: delay claim attempts, never expire user work.
    static final long CLAIM_RETRY_BASE_MILLIS = 1_000L;
    static final long CLAIM_RETRY_MAX_MILLIS = 300_000L;
    private final LongSupplier clock;
    private volatile long claimRetryNotBefore;
    private volatile long claimRetryDelay = CLAIM_RETRY_BASE_MILLIS;
    private final String consumerId = "mmd-chat-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean polling = new AtomicBoolean();
    // Same discovery scale as the existing execution relay; only missed notifications wait for it.
    static final long RECOVERY_SCAN_MILLIS = 5_000L;
    private long scheduledAt = Long.MAX_VALUE;
    private long requestedAt = Long.MAX_VALUE;
    private volatile long lifecycleGeneration;
    private boolean ready;
    private boolean claimedWork;

    private volatile String previousFailure;
    private volatile int sameFailureCount;

    @Autowired
    public ChatBountyBootstrapRelay(AgentTaskBountyBootstrapOutboxService outbox,
            ChatBountyBootstrapAdmissionService admission, ChatTypedDeliberationContextService contexts,
            ScheduleService schedules, PlatformTransactionManager manager, FixedTenantAdmissionPolicy scope,
            @Value("${cyf.task.scheduling.node-id}") String node) {
        this(outbox, admission, contexts, schedules, manager, scope, node, System::currentTimeMillis);
    }

    ChatBountyBootstrapRelay(AgentTaskBountyBootstrapOutboxService outbox,
            ChatBountyBootstrapAdmissionService admission, ChatTypedDeliberationContextService contexts,
            ScheduleService schedules, PlatformTransactionManager manager, FixedTenantAdmissionPolicy scope,
            String node, LongSupplier clock) {
        if (node == null || node.isBlank())
            throw new IllegalArgumentException("Bootstrap recovery requires a scheduler node identity");
        this.clock = Objects.requireNonNull(clock);
        this.outbox = Objects.requireNonNull(outbox);
        this.admission = Objects.requireNonNull(admission);
        this.contexts = Objects.requireNonNull(contexts);
        this.schedules = Objects.requireNonNull(schedules);
        this.scheduleKey = new ScheduleKey(scope.clientId() == null ? "" : scope.clientId(), HANDLER, "recovery", node);
        this.notificationTransaction = new TransactionTemplate(manager);
        // Hints originate in Spring afterCommit: REQUIRED would reuse the already committed resource.
        // A missed hint remains recoverable by the persistent node wake and original business outbox.
        this.notificationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public String getName() { return HANDLER; }

    @Override public synchronized void start() {
        if (contexts.bootstrapReadiness() == Readiness.DISABLED
                || !running.compareAndSet(false, true)) return;
        ++lifecycleGeneration;
        ready = false;
        previousFailure = null;
        sameFailureCount = 0;
        claimRetryNotBefore = 0;
        claimRetryDelay = CLAIM_RETRY_BASE_MILLIS;
        requestedAt = scheduledAt = Long.MAX_VALUE;
    }

    @EventListener public synchronized void onApplicationReady(ApplicationReadyEvent event) {
        if (!running.get() || ready) return;
        arm(clock.getAsLong(), false);
        ready = true;
    }

    @EventListener
    public synchronized void onWorkAvailable(AgentTaskBountyBootstrapWakeup hint) {
        if (!running.get() || !ready) return;
        long at = Math.max(Math.max(clock.getAsLong(), hint.availableAt()), claimRetryNotBefore);
        requestedAt = Math.min(requestedAt, at);
        if (!polling.get()) arm(at, true);
    }

    // Local coalescing avoids a SQL write per identical hint; the durable key owns the actual deadline.
    private void arm(long at, boolean afterCommitHint) {
        if (!running.get() || at >= scheduledAt) return;
        if (afterCommitHint) notificationTransaction.executeWithoutResult(status -> schedules.signal(scheduleKey, at));
        else schedules.signal(scheduleKey, at);
        scheduledAt = at;
    }

    @Override public void executeScheduled(ScheduledInvocation invocation) {
        final long generation;
        synchronized (this) {
            if (!running.get()) return;
            generation = lifecycleGeneration;
            scheduledAt = Long.MAX_VALUE;
            if (!ready || polling.get()) {
                arm(clock.getAsLong() + 500L, false);
                return;
            }
            if (requestedAt <= clock.getAsLong()) requestedAt = Long.MAX_VALUE;
        }
        safePoll(generation);
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
        claimedWork = true;
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

    private void safePoll(long generation) {
        if (!running.get() || lifecycleGeneration != generation || !polling.compareAndSet(false, true)) return;
        try {
            if (!running.get() || lifecycleGeneration != generation) return;
            claimedWork = false;
            if (pollWhenReady()) {
                synchronized (this) {
                    if (lifecycleGeneration != generation) return;
                    previousFailure = null;
                    sameFailureCount = 0;
                }
            }
        } catch (RuntimeException failure) {
            // Never silently busy-loop an unchanged DB/schema/fence failure. The claim lease
            // remains authoritative and can be safely reclaimed after an operator fixes it.
            synchronized (this) {
                if (lifecycleGeneration != generation) return; // an old lifecycle cannot stop its replacement
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
            synchronized (this) {
                polling.set(false);
                if (running.get() && lifecycleGeneration == generation) {
                    long now = clock.getAsLong();
                    long next = claimedWork ? now : now + RECOVERY_SCAN_MILLIS;
                    if (contexts.bootstrapReadiness() == Readiness.INITIALIZING) next = now + 500L;
                    next = Math.min(next, requestedAt);
                    if (claimRetryNotBefore > now) next = claimRetryNotBefore;
                    arm(next, false);
                }
            }
        }
    }

    @Override public synchronized void stop() {
        running.set(false);
        ready = false;
        ++lifecycleGeneration;
        requestedAt = scheduledAt = Long.MAX_VALUE;
        // Do not delete durable node work or interrupt an already accepted business transaction.
        // A restarted process resumes the original business outbox through the same key.
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public void close() { stop(); }
}
