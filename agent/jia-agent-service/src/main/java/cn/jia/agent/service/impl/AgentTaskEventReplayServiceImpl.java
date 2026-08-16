package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventReplayService;
import cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplayBackpressureException;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplayCapacityException;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplaySignal;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncReason;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncRequired;
import cn.jia.agent.service.AgentTaskEventReplayService.TaskScope;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, live-first reconstruction of durable task events. */
@Named
public class AgentTaskEventReplayServiceImpl
        implements AgentTaskEventReplayService, AutoCloseable {
    static final ReplayPolicy DEFAULT_POLICY = new ReplayPolicy(
            256, 4096, 32, 8, Duration.ofSeconds(5));
    static final ReplayResourceLimits DEFAULT_RESOURCE_LIMITS =
            new ReplayResourceLimits(256, 32, 64);
    private static final int CATCH_UP_THREADS = 4;
    private static final int CATCH_UP_QUEUE_CAPACITY = 256;
    private static final long CLOSE_WAIT_MILLIS = 500L;
    private static final AtomicLong RESOURCE_SEQUENCE = new AtomicLong();

    private final AgentTaskEventDao eventDao;
    private final AgentTaskEventBroker broker;
    private final ExecutorService catchUpExecutor;
    private final ExecutorService deliveryExecutor;
    private final ScheduledExecutorService timerExecutor;
    private final ReplayPolicy policy;
    private final ReplayResourceLimits resourceLimits;
    private final boolean ownsExecutors;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<SubscriptionState> deliveryReservations = ConcurrentHashMap.newKeySet();
    private final Semaphore activeSubscriptionPermits;
    private final Semaphore deliveryPermits;
    private final AtomicInteger scheduledWorkerTasks = new AtomicInteger();
    private final AtomicInteger inFlightCatchUps = new AtomicInteger();
    private final AtomicInteger activeTimers = new AtomicInteger();
    private final AtomicInteger inFlightDeliveries = new AtomicInteger();

    @Inject
    public AgentTaskEventReplayServiceImpl(
            AgentTaskEventDao eventDao,
            AgentTaskEventBroker broker) {
        this(eventDao, broker,
                createCatchUpExecutor(),
                createDeliveryExecutor(DEFAULT_RESOURCE_LIMITS.maxDeliverySlots()),
                createTimerExecutor(),
                DEFAULT_POLICY,
                DEFAULT_RESOURCE_LIMITS,
                true);
    }

    AgentTaskEventReplayServiceImpl(
            AgentTaskEventDao eventDao,
            AgentTaskEventBroker broker,
            ExecutorService catchUpExecutor,
            ExecutorService deliveryExecutor,
            ScheduledExecutorService timerExecutor,
            ReplayPolicy policy,
            ReplayResourceLimits resourceLimits) {
        this(eventDao, broker, catchUpExecutor, deliveryExecutor, timerExecutor,
                policy, resourceLimits, false);
    }

    private AgentTaskEventReplayServiceImpl(
            AgentTaskEventDao eventDao,
            AgentTaskEventBroker broker,
            ExecutorService catchUpExecutor,
            ExecutorService deliveryExecutor,
            ScheduledExecutorService timerExecutor,
            ReplayPolicy policy,
            ReplayResourceLimits resourceLimits,
            boolean ownsExecutors) {
        this.eventDao = Objects.requireNonNull(eventDao, "eventDao is required");
        this.broker = Objects.requireNonNull(broker, "broker is required");
        this.catchUpExecutor = Objects.requireNonNull(
                catchUpExecutor, "catchUpExecutor is required");
        this.deliveryExecutor = Objects.requireNonNull(
                deliveryExecutor, "deliveryExecutor is required");
        this.timerExecutor = Objects.requireNonNull(timerExecutor, "timerExecutor is required");
        this.policy = Objects.requireNonNull(policy, "replay policy is required");
        this.resourceLimits = Objects.requireNonNull(
                resourceLimits, "resourceLimits is required");
        this.activeSubscriptionPermits = new Semaphore(
                resourceLimits.maxActiveSubscriptions());
        this.deliveryPermits = new Semaphore(resourceLimits.maxDeliverySlots());
        this.ownsExecutors = ownsExecutors;
    }

    @Override
    public Flux<ReplaySignal> replay(TaskScope scope, long afterVersion) {
        TaskScope requiredScope = Objects.requireNonNull(scope, "task scope is required");
        if (afterVersion < 0) {
            return Flux.error(new IllegalArgumentException(
                    "afterVersion must not be negative"));
        }
        if (closed.get()) {
            return Flux.error(new IllegalStateException("Task event replay service is closed"));
        }
        return Flux.create(sink -> new SubscriptionState(
                requiredScope, afterVersion, sink).start(), FluxSink.OverflowStrategy.ERROR);
    }

    @PreDestroy
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<SubscriptionState> subscriptions = new ArrayList<>(deliveryReservations);
        subscriptions.forEach(SubscriptionState::serviceClosed);
        if (ownsExecutors) {
            timerExecutor.shutdownNow();
            catchUpExecutor.shutdownNow();
            deliveryExecutor.shutdown();
            awaitTermination(timerExecutor);
            awaitTermination(catchUpExecutor);
            awaitTermination(deliveryExecutor);
        }
    }

    int activeSubscriptionCount() {
        return resourceLimits.maxActiveSubscriptions()
                - activeSubscriptionPermits.availablePermits();
    }

    int deliveryPermitCount() {
        return resourceLimits.maxDeliverySlots() - deliveryPermits.availablePermits();
    }

    int scheduledWorkerTaskCount() {
        return scheduledWorkerTasks.get();
    }

    int inFlightCatchUpCount() {
        return inFlightCatchUps.get();
    }

    int activeTimerCount() {
        return activeTimers.get();
    }

    int inFlightDeliveryCount() {
        return inFlightDeliveries.get();
    }

    int pendingTimerTaskCount() {
        if (timerExecutor instanceof ScheduledThreadPoolExecutor scheduled) {
            return scheduled.getQueue().size();
        }
        return -1;
    }

    int pendingDeliveryTaskCount() {
        if (deliveryExecutor instanceof ThreadPoolExecutor executor) {
            return executor.getQueue().size();
        }
        return -1;
    }

    private final class SubscriptionState {
        private final TaskScope scope;
        private final AgentTaskEventBroker.TaskScope wakeupScope;
        private final FluxSink<ReplaySignal> sink;
        private final ArrayBlockingQueue<DurableEvent> signalQueue =
                new ArrayBlockingQueue<>(resourceLimits.signalQueueCapacity());
        private final AtomicLong catchUpVersion;
        private final AtomicLong deliveredVersion;
        private final AtomicLong highestHintedVersion;
        private final AtomicLong lastDurableHighWater;
        private final AtomicInteger outstandingSignals = new AtomicInteger();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean workerScheduled = new AtomicBoolean();
        private final AtomicBoolean logicalStopped = new AtomicBoolean();
        private final AtomicBoolean logicalCleaned = new AtomicBoolean();
        private final AtomicBoolean downstreamCancelled = new AtomicBoolean();
        private final AtomicBoolean downstreamTerminated = new AtomicBoolean();
        private final AtomicBoolean timerCounted = new AtomicBoolean();
        private final AtomicBoolean activePermitHeld = new AtomicBoolean();
        private final AtomicBoolean deliveryPermitHeld = new AtomicBoolean();
        private final AtomicBoolean deliveryLaneStarted = new AtomicBoolean();
        private final AtomicBoolean deliveryWakePending = new AtomicBoolean();
        private final Semaphore deliveryWakeup = new Semaphore(0);
        private final AtomicReference<TerminalOutcome> terminalOutcome = new AtomicReference<>();
        private final AtomicReference<Disposable> liveSubscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timerTask = new AtomicReference<>();
        private final AtomicReference<TrackedCatchUpTask> workerTask = new AtomicReference<>();
        private final AtomicReference<DeliveryTask> deliveryTask = new AtomicReference<>();

        private SubscriptionState(
                TaskScope scope,
                long initialCursor,
                FluxSink<ReplaySignal> sink) {
            this.scope = scope;
            this.wakeupScope = new AgentTaskEventBroker.TaskScope(
                    scope.tenantId(), scope.clientId(), scope.taskId());
            this.sink = sink;
            this.catchUpVersion = new AtomicLong(initialCursor);
            this.deliveredVersion = new AtomicLong(initialCursor);
            this.highestHintedVersion = new AtomicLong(initialCursor);
            this.lastDurableHighWater = new AtomicLong();
        }

        private void start() {
            if (closed.get()) {
                sink.error(new IllegalStateException("Task event replay service is closed"));
                return;
            }
            if (!claimResources()) {
                sink.error(new ReplayCapacityException());
                return;
            }

            sink.onCancel(this::cancel);
            sink.onDispose(this::cancel);
            if (sink.isCancelled() || logicalStopped.get()) {
                cancel();
                return;
            }
            if (closed.get()) {
                stopBeforeStart();
                if (!sink.isCancelled()) {
                    sink.error(new IllegalStateException(
                            "Task event replay service is closed"));
                }
                return;
            }
            if (!startDeliveryLane()) {
                stopBeforeStart();
                if (!sink.isCancelled()) {
                    sink.error(new ReplayCapacityException());
                }
                return;
            }

            try {
                Disposable live = broker.stream(wakeupScope).subscribe(
                        this::observeWakeup,
                        ignored -> { /* Durable polling remains authoritative. */ },
                        () -> { /* Broker completion is not a cleanup dependency. */ });
                if (!liveSubscription.compareAndSet(null, live) || logicalStopped.get()) {
                    live.dispose();
                }
            } catch (RuntimeException brokerFailure) {
                terminalResync(ResyncReason.DURABLE_STATE_UNPROVABLE,
                        terminalCurrentVersion(), true);
                return;
            }
            if (logicalStopped.get()) {
                return;
            }

            try {
                timerCounted.set(true);
                activeTimers.incrementAndGet();
                ScheduledFuture<?> timer = timerExecutor.scheduleAtFixedRate(
                        this::triggerPeriodic,
                        policy.periodicCheck().toMillis(),
                        policy.periodicCheck().toMillis(),
                        TimeUnit.MILLISECONDS);
                timerTask.set(timer);
                if (logicalStopped.get() && timerTask.compareAndSet(timer, null)) {
                    timer.cancel(true);
                    decrementTimerCount();
                }
            } catch (RuntimeException timerFailure) {
                decrementTimerCount();
                terminalResync(ResyncReason.DURABLE_STATE_UNPROVABLE,
                        terminalCurrentVersion(), true);
                return;
            }

            triggerCatchUp();
        }

        private boolean claimResources() {
            if (!activeSubscriptionPermits.tryAcquire()) {
                return false;
            }
            activePermitHeld.set(true);
            if (!deliveryPermits.tryAcquire()) {
                releaseActivePermit();
                return false;
            }
            deliveryPermitHeld.set(true);
            deliveryReservations.add(this);
            return true;
        }

        private void stopBeforeStart() {
            logicalStopped.set(true);
            cleanupLogical(true);
            releaseDeliveryPermitIfIdle();
        }

        private void observeWakeup(TaskEventWakeup wakeup) {
            if (logicalStopped.get() || wakeup == null || wakeup.eventVersion() <= 0
                    || !wakeupScope.equals(wakeup.scope())) {
                return;
            }
            long previous = highestHintedVersion.getAndAccumulate(
                    wakeup.eventVersion(), Math::max);
            if (wakeup.eventVersion() > catchUpVersion.get()
                    && wakeup.eventVersion() > previous) {
                triggerCatchUp();
            }
        }

        private void triggerPeriodic() {
            if (!logicalStopped.get()) {
                triggerCatchUp();
            }
        }

        private void triggerCatchUp() {
            dirty.set(true);
            scheduleWorker();
        }

        private void scheduleWorker() {
            if (logicalStopped.get() || !workerScheduled.compareAndSet(false, true)) {
                return;
            }
            TrackedCatchUpTask task = new TrackedCatchUpTask(this);
            workerTask.set(task);
            if (logicalStopped.get()) {
                task.cancel(false);
                return;
            }
            try {
                catchUpExecutor.execute(task);
            } catch (RejectedExecutionException rejected) {
                // Saturation is a lossy trigger. The bounded periodic check retries later.
                task.suppressReschedule();
                task.cancel(false);
            }
        }

        private void runCatchUpWorker() {
            inFlightCatchUps.incrementAndGet();
            try {
                if (logicalStopped.get() || sink.isCancelled()) {
                    return;
                }
                dirty.set(false);
                catchUpDurable();
            } catch (TerminalReplay terminal) {
                terminalResync(terminal.reason, terminal.currentVersion, false);
            } catch (RuntimeException durableFailure) {
                if (!logicalStopped.get()) {
                    terminalResync(ResyncReason.DURABLE_STATE_UNPROVABLE,
                            terminalCurrentVersion(), false);
                }
            } finally {
                inFlightCatchUps.decrementAndGet();
            }
        }

        private void catchUpDurable() {
            int pagesRead = 0;
            int eventsQueued = 0;
            int highWaterReads = 0;
            long cursor = catchUpVersion.get();

            while (!logicalStopped.get() && !sink.isCancelled()) {
                if (Thread.currentThread().isInterrupted()) {
                    if (logicalStopped.get()) {
                        return;
                    }
                    throw new IllegalStateException("Task event replay worker was interrupted");
                }
                if (highWaterReads >= policy.maxHighWaterReads()) {
                    throw terminal(ResyncReason.REPLAY_BUDGET_EXHAUSTED,
                            terminalCurrentVersion());
                }

                Long currentValue = eventDao.findCurrentVersion(
                        scope.tenantId(), scope.clientId(), scope.taskId());
                highWaterReads++;
                if (currentValue == null || currentValue < 0) {
                    throw terminal(ResyncReason.DURABLE_STATE_UNPROVABLE,
                            terminalCurrentVersion());
                }
                long currentVersion = currentValue;
                lastDurableHighWater.set(currentVersion);
                highestHintedVersion.getAndAccumulate(currentVersion, Math::max);

                if (cursor > currentVersion) {
                    throw terminal(ResyncReason.CURSOR_AHEAD, currentVersion);
                }
                if (cursor == currentVersion) {
                    return;
                }

                Long earliestValue = eventDao.findEarliestVersion(
                        scope.tenantId(), scope.clientId(), scope.taskId());
                if (earliestValue == null) {
                    throw terminal(ResyncReason.HISTORY_GAP, currentVersion);
                }
                long earliestVersion = earliestValue;
                if (earliestVersion <= 0 || earliestVersion > currentVersion) {
                    throw terminal(ResyncReason.DURABLE_STATE_UNPROVABLE, currentVersion);
                }
                if (earliestVersion > cursor + 1) {
                    throw terminal(ResyncReason.RETENTION_GAP, currentVersion);
                }

                long targetVersion = currentVersion;
                while (cursor < targetVersion && !logicalStopped.get()) {
                    if (pagesRead >= policy.maxPages()
                            || eventsQueued >= policy.maxEvents()) {
                        throw terminal(ResyncReason.REPLAY_BUDGET_EXHAUSTED,
                                currentVersion);
                    }
                    List<AgentTaskEventEntity> page = eventDao.findAfterVersion(
                            scope.tenantId(), scope.clientId(), scope.taskId(),
                            cursor, policy.pageSize());
                    pagesRead++;
                    if (page == null) {
                        throw terminal(ResyncReason.DURABLE_STATE_UNPROVABLE,
                                currentVersion);
                    }
                    if (page.isEmpty() || page.size() > policy.pageSize()) {
                        throw terminal(ResyncReason.PAGE_GAP, currentVersion);
                    }

                    long pageExpected = cursor + 1;
                    long priorCursor = cursor;
                    for (AgentTaskEventEntity entity : page) {
                        validatePageItem(entity, pageExpected, currentVersion);
                        long version = entity.getEventVersion();
                        pageExpected++;
                        if (version > targetVersion) {
                            continue;
                        }
                        if (eventsQueued >= policy.maxEvents()) {
                            throw terminal(ResyncReason.REPLAY_BUDGET_EXHAUSTED,
                                    currentVersion);
                        }
                        if (!queueDurable(entity)) {
                            return;
                        }
                        eventsQueued++;
                        cursor = version;
                        catchUpVersion.set(cursor);
                    }
                    if (cursor == priorCursor
                            || (cursor < targetVersion && page.size() < policy.pageSize())) {
                        throw terminal(ResyncReason.PAGE_GAP, currentVersion);
                    }
                }
            }
        }

        private void validatePageItem(
                AgentTaskEventEntity entity,
                long expectedVersion,
                long currentVersion) {
            if (entity == null
                    || entity.getEventVersion() == null
                    || entity.getEventVersion() <= 0
                    || entity.getEventVersion() != expectedVersion
                    || !scope.tenantId().equals(entity.getTenantId())
                    || !scope.clientId().equals(entity.getClientId())
                    || !scope.taskId().equals(entity.getTaskId())
                    || entity.getEventId() == null
                    || entity.getEventType() == null
                    || entity.getActorType() == null
                    || entity.getAggregateType() == null
                    || entity.getAggregateId() == null
                    || entity.getEventJson() == null
                    || entity.getOccurredAt() == null
                    || entity.getOccurredAt() <= 0) {
                throw terminal(ResyncReason.PAGE_GAP, currentVersion);
            }
        }

        private boolean queueDurable(AgentTaskEventEntity entity) {
            if (logicalStopped.get() || sink.isCancelled()) {
                return false;
            }
            long requested = sink.requestedFromDownstream();
            if (requested <= outstandingSignals.get()) {
                terminalBackpressure(false, false);
                return false;
            }
            DurableEvent signal = new DurableEvent(
                    scope,
                    entity.getEventVersion(),
                    entity.getEventId(),
                    entity.getEventType(),
                    entity.getActorType(),
                    entity.getActorId(),
                    entity.getAggregateType(),
                    entity.getAggregateId(),
                    entity.getEventJson(),
                    entity.getOccurredAt());
            outstandingSignals.incrementAndGet();
            if (!signalQueue.offer(signal)) {
                outstandingSignals.decrementAndGet();
                terminalBackpressure(true, false);
                return false;
            }
            if (logicalStopped.get() || downstreamCancelled.get() || sink.isCancelled()) {
                if (signalQueue.remove(signal)) {
                    outstandingSignals.decrementAndGet();
                }
                return false;
            }
            signalDelivery();
            return !logicalStopped.get();
        }

        private boolean startDeliveryLane() {
            if (!deliveryLaneStarted.compareAndSet(false, true)) {
                return true;
            }
            DeliveryTask task = new DeliveryTask(this);
            deliveryTask.set(task);
            try {
                deliveryExecutor.execute(task);
                return true;
            } catch (RejectedExecutionException rejected) {
                deliveryTask.compareAndSet(task, null);
                deliveryLaneStarted.set(false);
                return false;
            }
        }

        private void signalDelivery() {
            if (deliveryWakePending.compareAndSet(false, true)) {
                deliveryWakeup.release();
            }
        }

        private void runDelivery() {
            while (!downstreamCancelled.get() && !sink.isCancelled()) {
                TerminalOutcome outcome = terminalOutcome.get();
                if (outcome instanceof BackpressureOutcome backpressure
                        && backpressure.discardQueued()) {
                    clearSignalQueue();
                }

                if (!signalQueue.isEmpty() && sink.requestedFromDownstream() <= 0) {
                    if (outcome instanceof ServiceClosedOutcome) {
                        clearSignalQueue();
                    } else {
                        terminalBackpressure(false, true);
                    }
                    continue;
                }
                DurableEvent signal = signalQueue.poll();
                if (signal != null) {
                    if (downstreamCancelled.get() || sink.isCancelled()) {
                        outstandingSignals.decrementAndGet();
                        return;
                    }
                    try {
                        sink.next(signal);
                        deliveredVersion.set(signal.eventVersion());
                    } catch (RuntimeException callbackFailure) {
                        terminalBackpressure(true, true);
                    } finally {
                        outstandingSignals.decrementAndGet();
                    }
                    continue;
                }

                outcome = terminalOutcome.get();
                if (outcome instanceof BackpressureOutcome) {
                    if (downstreamTerminated.compareAndSet(false, true)
                            && !sink.isCancelled()) {
                        sink.error(new ReplayBackpressureException());
                    }
                    return;
                }
                if (outcome instanceof ResyncOutcome resync) {
                    if (sink.requestedFromDownstream() <= 0) {
                        terminalBackpressure(false, true);
                        continue;
                    }
                    if (downstreamTerminated.compareAndSet(false, true)
                            && !sink.isCancelled()) {
                        sink.next(new ResyncRequired(
                                scope, resync.currentVersion(), resync.reason()));
                        sink.complete();
                    }
                    return;
                }
                if (outcome instanceof ServiceClosedOutcome) {
                    if (downstreamTerminated.compareAndSet(false, true)
                            && !sink.isCancelled()) {
                        sink.complete();
                    }
                    return;
                }
                try {
                    deliveryWakeup.acquire();
                    deliveryWakePending.set(false);
                } catch (InterruptedException interrupted) {
                    if (downstreamCancelled.get() || sink.isCancelled()) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            cancel();
        }

        private void terminalBackpressure(boolean discardQueued, boolean cancelWorker) {
            BackpressureOutcome replacement = new BackpressureOutcome(discardQueued);
            while (true) {
                TerminalOutcome existing = terminalOutcome.get();
                if (existing instanceof ServiceClosedOutcome) {
                    break;
                }
                if (existing instanceof BackpressureOutcome backpressure) {
                    if (discardQueued && !backpressure.discardQueued()) {
                        terminalOutcome.compareAndSet(existing, replacement);
                    }
                    break;
                }
                if (terminalOutcome.compareAndSet(existing, replacement)) {
                    break;
                }
            }
            logicalStopped.set(true);
            cleanupLogical(cancelWorker);
            if (discardQueued && terminalOutcome.get() instanceof BackpressureOutcome) {
                clearSignalQueue();
            }
            signalDelivery();
        }

        private void terminalResync(
                ResyncReason reason,
                long currentVersion,
                boolean cancelWorker) {
            if (!terminalOutcome.compareAndSet(null,
                    new ResyncOutcome(reason, Math.max(0L, currentVersion)))) {
                return;
            }
            logicalStopped.set(true);
            cleanupLogical(cancelWorker);
            signalDelivery();
        }

        private void serviceClosed() {
            terminalOutcome.compareAndSet(null, ServiceClosedOutcome.INSTANCE);
            logicalStopped.set(true);
            cleanupLogical(true);
            signalDelivery();
        }

        private long terminalCurrentVersion() {
            return Math.max(0L, lastDurableHighWater.get());
        }

        private TerminalReplay terminal(ResyncReason reason, long currentVersion) {
            return new TerminalReplay(reason, Math.max(0L, currentVersion));
        }

        private void cancel() {
            downstreamCancelled.set(true);
            logicalStopped.set(true);
            clearSignalQueue();
            cleanupLogical(true);
            if (!downstreamTerminated.get()) {
                signalDelivery();
                DeliveryTask task = deliveryTask.get();
                if (task != null) {
                    task.interrupt();
                }
            }
            releaseDeliveryPermitIfIdle();
        }

        private void cleanupLogical(boolean cancelWorker) {
            if (!logicalCleaned.compareAndSet(false, true)) {
                return;
            }
            dirty.set(false);
            highestHintedVersion.set(catchUpVersion.get());
            Disposable live = liveSubscription.getAndSet(null);
            if (live != null) {
                live.dispose();
            }
            ScheduledFuture<?> timer = timerTask.getAndSet(null);
            if (timer != null) {
                timer.cancel(true);
            }
            decrementTimerCount();
            if (cancelWorker) {
                TrackedCatchUpTask worker = workerTask.getAndSet(null);
                if (worker != null) {
                    worker.cancel(true);
                }
            }
            releaseActivePermit();
        }

        private void clearSignalQueue() {
            int removed = 0;
            while (signalQueue.poll() != null) {
                removed++;
            }
            if (removed != 0) {
                outstandingSignals.addAndGet(-removed);
            }
        }

        private void releaseActivePermit() {
            if (activePermitHeld.compareAndSet(true, false)) {
                activeSubscriptionPermits.release();
            }
        }

        private void releaseDeliveryPermitIfIdle() {
            if (!deliveryLaneStarted.get()) {
                releaseDeliveryPermit();
            }
        }

        private void releaseDeliveryPermit() {
            if (deliveryPermitHeld.compareAndSet(true, false)) {
                deliveryReservations.remove(this);
                deliveryPermits.release();
            }
        }

        private void decrementTimerCount() {
            if (timerCounted.compareAndSet(true, false)) {
                activeTimers.decrementAndGet();
            }
        }

        private void deliveryExited(DeliveryTask task) {
            deliveryTask.compareAndSet(task, null);
            deliveryLaneStarted.set(false);
            releaseDeliveryPermit();
        }
    }

    private final class TrackedCatchUpTask extends FutureTask<Void> {
        private final SubscriptionState state;
        private final AtomicBoolean counted = new AtomicBoolean(true);
        private final AtomicBoolean rescheduleAllowed = new AtomicBoolean(true);

        private TrackedCatchUpTask(SubscriptionState state) {
            super(state::runCatchUpWorker, null);
            this.state = state;
            scheduledWorkerTasks.incrementAndGet();
        }

        private void suppressReschedule() {
            rescheduleAllowed.set(false);
        }

        @Override
        protected void done() {
            if (counted.compareAndSet(true, false)) {
                scheduledWorkerTasks.decrementAndGet();
            }
            state.workerTask.compareAndSet(this, null);
            state.workerScheduled.set(false);
            if (rescheduleAllowed.get()
                    && state.dirty.get() && !state.logicalStopped.get()) {
                state.scheduleWorker();
            }
        }
    }

    private final class DeliveryTask implements Runnable {
        private final SubscriptionState state;
        private final AtomicReference<Thread> runner = new AtomicReference<>();

        private DeliveryTask(SubscriptionState state) {
            this.state = state;
        }

        @Override
        public void run() {
            runner.set(Thread.currentThread());
            inFlightDeliveries.incrementAndGet();
            try {
                state.runDelivery();
            } finally {
                runner.set(null);
                inFlightDeliveries.decrementAndGet();
                state.deliveryExited(this);
            }
        }

        private void interrupt() {
            Thread thread = runner.get();
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    private sealed interface TerminalOutcome
            permits BackpressureOutcome, ResyncOutcome, ServiceClosedOutcome {
    }

    private record BackpressureOutcome(boolean discardQueued) implements TerminalOutcome {
    }

    private record ResyncOutcome(
            ResyncReason reason,
            long currentVersion) implements TerminalOutcome {
    }

    private enum ServiceClosedOutcome implements TerminalOutcome {
        INSTANCE
    }

    record ReplayPolicy(
            int pageSize,
            int maxEvents,
            int maxPages,
            int maxHighWaterReads,
            Duration periodicCheck) {
        ReplayPolicy {
            if (pageSize <= 0 || pageSize > AgentTaskEventDao.MAX_REPLAY_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize is outside the DAO limit");
            }
            if (maxEvents <= 0 || maxPages <= 0 || maxHighWaterReads <= 0) {
                throw new IllegalArgumentException("replay budgets must be positive");
            }
            periodicCheck = Objects.requireNonNull(periodicCheck, "periodicCheck is required");
            if (periodicCheck.isZero() || periodicCheck.isNegative()
                    || periodicCheck.toMillis() <= 0) {
                throw new IllegalArgumentException("periodicCheck must be at least one millisecond");
            }
        }
    }

    record ReplayResourceLimits(
            int signalQueueCapacity,
            int maxActiveSubscriptions,
            int maxDeliverySlots) {
        ReplayResourceLimits {
            if (signalQueueCapacity <= 0
                    || maxActiveSubscriptions <= 0
                    || maxDeliverySlots <= 0) {
                throw new IllegalArgumentException("replay resource limits must be positive");
            }
            if (maxDeliverySlots < maxActiveSubscriptions) {
                throw new IllegalArgumentException(
                        "delivery slots must cover all active subscriptions");
            }
        }
    }

    private static final class TerminalReplay extends RuntimeException {
        private final ResyncReason reason;
        private final long currentVersion;

        private TerminalReplay(ResyncReason reason, long currentVersion) {
            super(null, null, false, false);
            this.reason = reason;
            this.currentVersion = currentVersion;
        }
    }

    private static ExecutorService createCatchUpExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CATCH_UP_THREADS,
                CATCH_UP_THREADS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CATCH_UP_QUEUE_CAPACITY),
                daemonThreadFactory("agent-task-replay-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ExecutorService createDeliveryExecutor(int maxDeliverySlots) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                maxDeliverySlots,
                maxDeliverySlots,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxDeliverySlots),
                daemonThreadFactory("agent-task-replay-delivery"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ScheduledExecutorService createTimerExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, daemonThreadFactory("agent-task-replay-timer"));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        long resourceId = RESOURCE_SEQUENCE.incrementAndGet();
        AtomicLong threadSequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    prefix + "-" + resourceId + "-" + threadSequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            executor.awaitTermination(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
