package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO.Outcome;
import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final String consumerId = "mmd-chat-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile String previousFailure;
    private volatile int sameFailureCount;

    public ChatBountyBootstrapRelay(AgentTaskBountyBootstrapOutboxService outbox,
            ChatBountyBootstrapAdmissionService admission) {
        this.outbox = Objects.requireNonNull(outbox);
        this.admission = Objects.requireNonNull(admission);
    }

    @Override public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-bounty-bootstrap");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safePoll, 0, 500, TimeUnit.MILLISECONDS);
    }

    /** One persistent claim per poll, so a retry never spins on the same row in one loop. */
    void pollOnce() {
        long now = System.currentTimeMillis();
        AgentTaskBountyBootstrapClaimDTO claim = outbox.claimNextAvailable(consumerId, now);
        if (claim == null) return;
        AgentTaskExecutionGrantService.Scope scope = new AgentTaskExecutionGrantService.Scope(
                claim.tenantId(), claim.clientId(), claim.ownerJiacn());
        ChatBountyBootstrapAdmissionService.Admission result;
        try {
            result = admission.admit(claim); // transactional commit before the acknowledgement below
        } catch (RuntimeException failure) {
            // A missing owner-scoped revision or a revoked grant must never be replaced by a
            // truncated task-plan projection or a text CHAT dispatch. Retain for reconciliation.
            log.warn("Bounty bootstrap admission failed, will retry with original intent", failure);
            reconcile(scope, claim, Outcome.RETRYABLE_FAILURE, null, null,
                    "BOUNTY_ADMISSION_UNAVAILABLE");
            throw new IllegalStateException("BOUNTY_ADMISSION_UNAVAILABLE", failure);
        }
        reconcile(scope, claim, Outcome.ADMITTED, result.conversationId(), result.requestId(), null);
    }

    private void reconcile(AgentTaskExecutionGrantService.Scope scope,
            AgentTaskBountyBootstrapClaimDTO claim, Outcome outcome, String conversationId,
            String requestId, String errorCode) {
        var response = outbox.reconcile(scope, new AgentTaskBountyBootstrapReconcileDTO(
                claim.bootstrapId(), claim.outboxVersion(), claim.leaseOwner(), claim.claimAttempt(),
                outcome, conversationId, requestId, errorCode), System.currentTimeMillis());
        if (response == null || !claim.bootstrapId().equals(response.bootstrapId())
                || outcome == Outcome.ADMITTED && (!"ADMITTED".equals(response.status())
                || !Objects.equals(conversationId, response.conversationId())
                || !Objects.equals(requestId, response.initialRequestId()))) {
            throw new IllegalStateException("Bounty bootstrap reconciliation was not durable");
        }
    }

    private void safePoll() {
        if (!running.get()) return;
        try {
            pollOnce();
            previousFailure = null;
            sameFailureCount = 0;
        } catch (RuntimeException failure) {
            // Never silently busy-loop an unchanged DB/schema/fence failure. The claim lease
            // remains authoritative and can be safely reclaimed after an operator fixes it.
            String signature = failure.getClass().getName() + ":" + failure.getMessage();
            sameFailureCount = Objects.equals(signature, previousFailure) ? sameFailureCount + 1 : 1;
            previousFailure = signature;
            log.warn("Bounty bootstrap relay cannot progress", failure);
            if (sameFailureCount >= 2) {
                log.error("Bounty bootstrap relay stopped after repeated unchanged failure");
                stop();
            }
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
