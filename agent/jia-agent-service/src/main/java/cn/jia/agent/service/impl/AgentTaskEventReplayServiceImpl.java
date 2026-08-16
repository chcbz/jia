package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventReplayService;
import cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplayBackpressureException;
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
    private static final int WORKER_THREADS = 4;
    private static final int WORKER_QUEUE_CAPACITY = 256;
    private static final long CLOSE_WAIT_MILLIS = 500L;
    private static final AtomicLong RESOURCE_SEQUENCE = new AtomicLong();

    private final AgentTaskEventDao eventDao;
    private final AgentTaskEventBroker broker;
    private final ExecutorService catchUpExecutor;
    private final ScheduledExecutorService timerExecutor;
    private final ReplayPolicy policy;
    private final boolean ownsExecutors;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<SubscriptionState> activeSubscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger scheduledWorkerTasks = new AtomicInteger();
    private final AtomicInteger inFlightCatchUps = new AtomicInteger();
    private final AtomicInteger activeTimers = new AtomicInteger();

    @Inject
    public AgentTaskEventReplayServiceImpl(
            AgentTaskEventDao eventDao,
            AgentTaskEventBroker broker) {
        this(eventDao, broker, createCatchUpExecutor(), createTimerExecutor(),
                DEFAULT_POLICY, true);
    }

    AgentTaskEventReplayServiceImpl(
            AgentTaskEventDao eventDao,
            AgentTaskEventBroker broker,
            ExecutorService catchUpExecutor,
            ScheduledExecutorService timerExecutor,
            ReplayPolicy policy) {
        this(eventDao, broker, catchUpExecutor, timerExecutor, policy, false);
    }

    private AgentTaskEventReplayServiceImpl(
            AgentTaskEventDao eventDao,
            AgentTaskEventBroker broker,
            ExecutorService catchUpExecutor,
            ScheduledExecutorService timerExecutor,
            ReplayPolicy policy,
            boolean ownsExecutors) {
        this.eventDao = Objects.requireNonNull(eventDao, "eventDao is required");
        this.broker = Objects.requireNonNull(broker, "broker is required");
        this.catchUpExecutor = Objects.requireNonNull(
                catchUpExecutor, "catchUpExecutor is required");
        this.timerExecutor = Objects.requireNonNull(timerExecutor, "timerExecutor is required");
        this.policy = Objects.requireNonNull(policy, "replay policy is required");
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
        List<SubscriptionState> subscriptions = new ArrayList<>(activeSubscriptions);
        subscriptions.forEach(SubscriptionState::cancel);
        if (ownsExecutors) {
            timerExecutor.shutdownNow();
            catchUpExecutor.shutdownNow();
            awaitTermination(timerExecutor);
            awaitTermination(catchUpExecutor);
        }
    }

    int activeSubscriptionCount() {
        return activeSubscriptions.size();
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

    private final class SubscriptionState {
        private final TaskScope scope;
        private final AgentTaskEventBroker.TaskScope wakeupScope;
        private final FluxSink<ReplaySignal> sink;
        private final AtomicLong deliveredVersion;
        private final AtomicLong highestHintedVersion;
        private final AtomicLong lastDurableHighWater;
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean workerScheduled = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicBoolean timerCounted = new AtomicBoolean();
        private final AtomicReference<Disposable> liveSubscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timerTask = new AtomicReference<>();
        private final AtomicReference<TrackedCatchUpTask> workerTask = new AtomicReference<>();

        private SubscriptionState(
                TaskScope scope,
                long initialCursor,
                FluxSink<ReplaySignal> sink) {
            this.scope = scope;
            this.wakeupScope = new AgentTaskEventBroker.TaskScope(
                    scope.tenantId(), scope.clientId(), scope.taskId());
            this.sink = sink;
            this.deliveredVersion = new AtomicLong(initialCursor);
            this.highestHintedVersion = new AtomicLong(initialCursor);
            this.lastDurableHighWater = new AtomicLong();
        }

        private void start() {
            if (closed.get()) {
                sink.error(new IllegalStateException("Task event replay service is closed"));
                return;
            }
            activeSubscriptions.add(this);
            sink.onCancel(this::cancel);
            sink.onDispose(this::cancel);
            if (closed.get()) {
                cancel();
                if (!sink.isCancelled()) {
                    sink.error(new IllegalStateException(
                            "Task event replay service is closed"));
                }
                return;
            }

            Disposable live = broker.stream(wakeupScope).subscribe(
                    this::observeWakeup,
                    ignored -> { /* Durable polling remains authoritative. */ },
                    () -> { /* Broker shutdown completion is not a cleanup dependency. */ });
            if (!liveSubscription.compareAndSet(null, live) || terminated.get()) {
                live.dispose();
            }
            if (terminated.get()) {
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
                if (terminated.get() && timerTask.compareAndSet(timer, null)) {
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

        private void observeWakeup(TaskEventWakeup wakeup) {
            if (terminated.get() || wakeup == null || wakeup.eventVersion() <= 0
                    || !wakeupScope.equals(wakeup.scope())) {
                return;
            }
            long previous = highestHintedVersion.getAndAccumulate(
                    wakeup.eventVersion(), Math::max);
            if (wakeup.eventVersion() > deliveredVersion.get()
                    && wakeup.eventVersion() > previous) {
                triggerCatchUp();
            }
        }

        private void triggerPeriodic() {
            if (!terminated.get()) {
                triggerCatchUp();
            }
        }

        private void triggerCatchUp() {
            dirty.set(true);
            scheduleWorker();
        }

        private void scheduleWorker() {
            if (terminated.get() || !workerScheduled.compareAndSet(false, true)) {
                return;
            }
            TrackedCatchUpTask task = new TrackedCatchUpTask(this);
            workerTask.set(task);
            if (terminated.get()) {
                task.cancel(false);
                return;
            }
            try {
                catchUpExecutor.execute(task);
            } catch (RejectedExecutionException rejected) {
                // Saturation is another lossy trigger. Never run downstream callbacks on the
                // broker, timer, or subscribing thread; the periodic check will retry later.
                task.suppressReschedule();
                task.cancel(false);
            }
        }

        private void runCatchUpWorker() {
            inFlightCatchUps.incrementAndGet();
            try {
                if (terminated.get() || sink.isCancelled()) {
                    return;
                }
                dirty.set(false);
                catchUpDurable();
            } catch (TerminalReplay terminal) {
                terminalResync(terminal.reason, terminal.currentVersion, false);
            } catch (RuntimeException durableFailure) {
                if (!terminated.get()) {
                    terminalResync(ResyncReason.DURABLE_STATE_UNPROVABLE,
                            terminalCurrentVersion(), false);
                }
            } finally {
                inFlightCatchUps.decrementAndGet();
            }
        }

        private void catchUpDurable() {
            int pagesRead = 0;
            int eventsEmitted = 0;
            int highWaterReads = 0;
            long cursor = deliveredVersion.get();

            while (!terminated.get() && !sink.isCancelled()) {
                if (Thread.currentThread().isInterrupted()) {
                    if (terminated.get()) {
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
                while (cursor < targetVersion) {
                    if (pagesRead >= policy.maxPages()
                            || eventsEmitted >= policy.maxEvents()) {
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
                        if (eventsEmitted >= policy.maxEvents()) {
                            throw terminal(ResyncReason.REPLAY_BUDGET_EXHAUSTED,
                                    currentVersion);
                        }
                        if (!emitDurable(entity)) {
                            return;
                        }
                        eventsEmitted++;
                        cursor = version;
                        deliveredVersion.set(cursor);
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

        private boolean emitDurable(AgentTaskEventEntity entity) {
            if (terminated.get() || sink.isCancelled()) {
                return false;
            }
            if (sink.requestedFromDownstream() <= 0) {
                terminalBackpressure();
                return false;
            }
            sink.next(new DurableEvent(
                    scope,
                    entity.getEventVersion(),
                    entity.getEventId(),
                    entity.getEventType(),
                    entity.getActorType(),
                    entity.getActorId(),
                    entity.getAggregateType(),
                    entity.getAggregateId(),
                    entity.getEventJson(),
                    entity.getOccurredAt()));
            return !terminated.get() && !sink.isCancelled();
        }

        private void terminalBackpressure() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cleanup(false);
            if (!sink.isCancelled()) {
                sink.error(new ReplayBackpressureException());
            }
        }

        private void terminalResync(
                ResyncReason reason,
                long currentVersion,
                boolean cancelWorker) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cleanup(cancelWorker);
            if (sink.isCancelled()) {
                return;
            }
            if (sink.requestedFromDownstream() <= 0) {
                sink.error(new ReplayBackpressureException());
                return;
            }
            sink.next(new ResyncRequired(scope, Math.max(0L, currentVersion), reason));
            sink.complete();
        }

        private long terminalCurrentVersion() {
            return Math.max(0L, lastDurableHighWater.get());
        }

        private TerminalReplay terminal(ResyncReason reason, long currentVersion) {
            return new TerminalReplay(reason, Math.max(0L, currentVersion));
        }

        private void cancel() {
            terminated.set(true);
            cleanup(true);
        }

        private void cleanup(boolean cancelWorker) {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            dirty.set(false);
            highestHintedVersion.set(deliveredVersion.get());
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
            activeSubscriptions.remove(this);
        }

        private void decrementTimerCount() {
            if (timerCounted.compareAndSet(true, false)) {
                activeTimers.decrementAndGet();
            }
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
                    && state.dirty.get() && !state.terminated.get()) {
                state.scheduleWorker();
            }
        }
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
                WORKER_THREADS,
                WORKER_THREADS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                daemonThreadFactory("agent-task-replay-worker"),
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
