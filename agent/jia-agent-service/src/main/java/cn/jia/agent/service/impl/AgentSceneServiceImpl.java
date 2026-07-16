package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentSceneConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentSceneEventDao;
import cn.jia.agent.dao.AgentScenePhaseReportDao;
import cn.jia.agent.dao.AgentSceneStateDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentSceneAgentDTO;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.entity.AgentSceneStateEntity;
import cn.jia.agent.service.AgentSceneEventBroker;
import cn.jia.agent.service.AgentSceneEventBroker.SceneScope;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Named
public class AgentSceneServiceImpl implements AgentSceneService {
    private static final String EVENT_STATE_UPDATED = "agent-scene-state-updated";
    private static final String EVENT_RESYNC_REQUIRED = "resync-required";
    private static final int BACKLOG_PAGE_SIZE = 1000;
    private static final Set<String> VISIBLE_STATUSES = Set.of(
            AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY);

    private final AgentSceneStateDao stateDao;
    private final AgentSceneEventDao eventDao;
    private final AgentScenePhaseReportDao phaseReportDao;
    private final AgentRuntimeDao runtimeDao;
    private final AgentSceneEventBroker eventBroker;
    private final AtomicInteger scheduledEventBridgeTasks = new AtomicInteger();

    @Inject
    public AgentSceneServiceImpl(
            AgentSceneStateDao stateDao,
            AgentSceneEventDao eventDao,
            AgentScenePhaseReportDao phaseReportDao,
            AgentRuntimeDao runtimeDao,
            AgentSceneEventBroker eventBroker) {
        this.stateDao = stateDao;
        this.eventDao = eventDao;
        this.phaseReportDao = phaseReportDao;
        this.runtimeDao = runtimeDao;
        this.eventBroker = eventBroker;
    }

    public AgentSceneServiceImpl(
            AgentSceneStateDao stateDao,
            AgentSceneEventDao eventDao,
            AgentRuntimeDao runtimeDao,
            AgentSceneEventBroker eventBroker) {
        this(stateDao, eventDao, null, runtimeDao, eventBroker);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentSceneSnapshotDTO snapshot(String sceneId) {
        SceneScope scope = requireScope(sceneId);
        long now = System.currentTimeMillis();
        List<AgentRuntimeEntity> roster = scopedRoster(scope);
        Map<String, AgentRuntimeEntity> visibleByAgent = new HashMap<>();
        List<AgentSceneAgentDTO> agents = new ArrayList<>();
        for (AgentRuntimeEntity runtime : roster) {
            if (!VISIBLE_STATUSES.contains(runtime.getStatus())
                    || StringUtil.isBlank(runtime.getAgentId())
                    || StringUtil.isBlank(runtime.getPersonaCode())) {
                continue;
            }
            visibleByAgent.put(runtime.getAgentId(), runtime);
            agents.add(toAgentDTO(runtime));
        }
        agents.sort(Comparator.comparing(AgentSceneAgentDTO::getAgentId));

        List<AgentSceneStateDTO> states = safeList(stateDao.findActiveByScene(
                scope.tenantId(), scope.clientId(), scope.sceneId(), now)).stream()
                .filter(entity -> isActive(entity, now))
                .filter(entity -> stateMatchesVisibleAgent(entity, visibleByAgent))
                .map(AgentSceneServiceImpl::toStateDTO)
                .sorted(Comparator.comparing(AgentSceneStateDTO::getAgentId))
                .toList();

        Long currentVersion = eventDao.findLatestSceneVersion(
                scope.tenantId(), scope.clientId(), scope.sceneId());
        AgentSceneSnapshotDTO snapshot = new AgentSceneSnapshotDTO();
        snapshot.setSceneId(scope.sceneId());
        snapshot.setSceneVersion(currentVersion == null ? 0L : currentVersion);
        snapshot.setGeneratedAt(now);
        snapshot.setAgents(agents);
        snapshot.setStates(states);
        return snapshot;
    }

    @Override
    public Flux<AgentSceneEventDTO> events(String sceneId, long sinceVersion) {
        SceneScope scope = requireScope(sceneId);
        if (sinceVersion < 0) {
            return Flux.error(new IllegalArgumentException("sinceVersion must be nonnegative"));
        }
        return Flux.create(sink -> bridgeBacklogAndLive(scope, sinceVersion, sink),
                FluxSink.OverflowStrategy.ERROR);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentScenePhaseResultDTO reportPhase(String sceneId, AgentScenePhaseReportDTO request) {
        SceneScope scope = requireScope(sceneId);
        AgentScenePhaseReportDTO report = requirePhaseReport(request);
        if (phaseReportDao == null) {
            throw new IllegalStateException("AgentScenePhaseReportDao is required for phase reporting");
        }
        AgentScenePhaseReportEntity existing = phaseReportDao.findByReportId(
                scope.tenantId(), scope.clientId(), scope.sceneId(), report.getReportId());
        if (existing != null) {
            return phaseResult(existing.getReportId(), existing.getStateVersion(),
                    AgentSceneConstants.RESULT_IGNORED_DUPLICATE);
        }

        AgentSceneStateEntity current = stateDao.findByAgent(
                scope.tenantId(), scope.clientId(), scope.sceneId(), report.getAgentId());
        boolean exactCurrent = phaseMatchesCurrent(report, current);
        long processedAt = System.currentTimeMillis();
        String initialResult = exactCurrent
                ? AgentSceneConstants.RESULT_ACCEPTED
                : AgentSceneConstants.RESULT_IGNORED_STALE;
        AgentScenePhaseReportEntity persisted = toPhaseReportEntity(report, initialResult, processedAt);
        try {
            if (phaseReportDao.insert(scope.tenantId(), scope.clientId(), scope.sceneId(), persisted) <= 0) {
                throw new IllegalStateException("Unable to persist agent scene phase report");
            }
        } catch (DuplicateKeyException duplicate) {
            AgentScenePhaseReportEntity original = phaseReportDao.findByReportId(
                    scope.tenantId(), scope.clientId(), scope.sceneId(), report.getReportId());
            if (original == null) {
                throw new IllegalStateException("Duplicate phase report is not readable in its scope", duplicate);
            }
            return phaseResult(original.getReportId(), original.getStateVersion(),
                    AgentSceneConstants.RESULT_IGNORED_DUPLICATE);
        }

        if (!exactCurrent) {
            return phaseResult(report.getReportId(), report.getStateVersion(),
                    AgentSceneConstants.RESULT_IGNORED_STALE);
        }

        int updated = stateDao.updatePhase(
                scope.tenantId(), scope.clientId(), scope.sceneId(), report.getAgentId(),
                report.getStateVersion(), report.getPhase(), processedAt);
        if (updated <= 0) {
            if (phaseReportDao.updateResult(
                    scope.tenantId(), scope.clientId(), scope.sceneId(), report.getReportId(),
                    AgentSceneConstants.RESULT_IGNORED_STALE, processedAt) <= 0) {
                throw new IllegalStateException("Unable to persist stale phase report result");
            }
            return phaseResult(report.getReportId(), report.getStateVersion(),
                    AgentSceneConstants.RESULT_IGNORED_STALE);
        }

        long sceneVersion = eventDao.nextSceneVersion(
                scope.tenantId(), scope.clientId(), scope.sceneId());
        if (sceneVersion <= 0) {
            throw new IllegalStateException("Allocated sceneVersion must be positive");
        }
        AgentSceneStateDTO publishedState = toStateDTO(current);
        publishedState.setPhase(report.getPhase());
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(sceneVersion);
        event.setEventType(EVENT_STATE_UPDATED);
        event.setState(publishedState);
        event.setOccurredAt(processedAt);
        if (eventDao.insert(scope.tenantId(), scope.clientId(), scope.sceneId(), event) <= 0) {
            throw new IllegalStateException("Unable to persist accepted phase event");
        }
        publishAfterCommit(scope, event);
        return phaseResult(report.getReportId(), report.getStateVersion(),
                AgentSceneConstants.RESULT_ACCEPTED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentSceneStateDTO upsertState(String sceneId, AgentSceneStateDTO state) {
        SceneScope scope = requireScope(sceneId);
        AgentSceneStateDTO requested = requireState(state);

        /*
         * This must remain the first normal/consistent database access in the transaction.
         * Under InnoDB REPEATABLE READ, the counter INSERT/UPDATE takes the scoped row lock
         * before the transaction establishes snapshots for roster/state decisions. Same-scene
         * writers therefore serialize persona and stateVersion decisions until commit/rollback.
         */
        long sceneVersion = eventDao.nextSceneVersion(
                scope.tenantId(), scope.clientId(), scope.sceneId());
        if (sceneVersion <= 0) {
            throw new IllegalStateException("Allocated sceneVersion must be positive");
        }
        long now = System.currentTimeMillis();
        List<AgentRuntimeEntity> roster = scopedRoster(scope);
        Map<String, AgentRuntimeEntity> realAgents = indexRealAgents(roster);
        AgentRuntimeEntity requester = realAgents.get(requested.getAgentId());
        if (requester == null) {
            throw new IllegalArgumentException("agentId is not a real agent in the current scope");
        }
        if (!requested.getPersonaCode().equals(requester.getPersonaCode())) {
            throw new IllegalArgumentException("personaCode does not match the scoped real agent");
        }
        AgentSceneStateEntity current = stateDao.findByAgent(
                scope.tenantId(), scope.clientId(), scope.sceneId(), requested.getAgentId());
        rejectPersonaConflict(scope, requested, realAgents, now);
        long currentStateVersion = current == null || current.getStateVersion() == null
                ? 0L : current.getStateVersion();
        if (currentStateVersion == Long.MAX_VALUE) {
            throw new IllegalStateException("stateVersion exhausted");
        }
        long nextStateVersion = currentStateVersion + 1;
        AgentSceneStateEntity persisted = toStateEntity(requested, nextStateVersion);
        if (stateDao.upsert(scope.tenantId(), scope.clientId(), scope.sceneId(), persisted) <= 0) {
            throw new IllegalStateException("Unable to persist monotonic scene state");
        }

        AgentSceneStateDTO publishedState = toStateDTO(persisted);
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(sceneVersion);
        event.setEventType(EVENT_STATE_UPDATED);
        event.setState(publishedState);
        event.setOccurredAt(now);
        if (eventDao.insert(scope.tenantId(), scope.clientId(), scope.sceneId(), event) <= 0) {
            throw new IllegalStateException("Unable to persist scene state event");
        }
        publishAfterCommit(scope, event);
        return AgentSceneStateDTO.copyOf(publishedState);
    }

    private void bridgeBacklogAndLive(
            SceneScope scope, long sinceVersion, FluxSink<AgentSceneEventDTO> sink) {
        new EventStreamBridge(scope, sinceVersion, sink).start();
    }

    int scheduledEventBridgeTaskCount() {
        // Package-visible only so the bounded-work regression can assert coalescing.
        return scheduledEventBridgeTasks.get();
    }

    private List<AgentSceneEventDTO> readContiguousEvents(
            SceneScope scope, long afterVersion, long throughVersion) {
        if (throughVersion <= afterVersion) {
            return List.of();
        }
        List<AgentSceneEventDTO> backlog = new ArrayList<>();
        long cursor = afterVersion;
        while (cursor < throughVersion) {
            List<AgentSceneEventEntity> page = safeList(eventDao.findAfterVersion(
                    scope.tenantId(), scope.clientId(), scope.sceneId(), cursor, BACKLOG_PAGE_SIZE));
            if (page.isEmpty()) {
                throw new ContinuityGapException();
            }
            long priorCursor = cursor;
            for (AgentSceneEventEntity entity : page) {
                if (entity == null || entity.getSceneVersion() == null
                        || entity.getSceneVersion() <= cursor) {
                    continue;
                }
                long expectedVersion = cursor + 1;
                if (entity.getSceneVersion() != expectedVersion
                        || entity.getSceneVersion() > throughVersion) {
                    throw new ContinuityGapException();
                }
                AgentSceneEventDTO event = toEventDTO(entity);
                backlog.add(event);
                cursor = entity.getSceneVersion();
                if (cursor == throughVersion) {
                    break;
                }
            }
            if (cursor == priorCursor
                    || (cursor < throughVersion && page.size() < BACKLOG_PAGE_SIZE)) {
                throw new ContinuityGapException();
            }
        }
        return backlog;
    }

    private static AgentSceneEventDTO toEventDTO(AgentSceneEventEntity entity) {
        AgentSceneEventDTO stored = JsonUtil.fromJson(entity.getEventJson(), AgentSceneEventDTO.class);
        if (stored == null) {
            throw new IllegalStateException(
                    "Unable to deserialize persisted scene event " + entity.getSceneVersion());
        }
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(entity.getSceneVersion());
        event.setEventType(entity.getEventType());
        event.setState(stored.getState());
        event.setOccurredAt(entity.getOccurredAt());
        return event;
    }

    private static AgentSceneEventDTO resyncRequired(long currentVersion) {
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(currentVersion);
        event.setEventType(EVENT_RESYNC_REQUIRED);
        return event;
    }

    private final class EventStreamBridge {
        private final SceneScope scope;
        private final long sinceVersion;
        private final FluxSink<AgentSceneEventDTO> sink;
        private final Scheduler.Worker worker = Schedulers.boundedElastic().createWorker();
        private final AtomicLong deliveredVersion;
        private final AtomicLong highestObservedVersion;
        private final AtomicBoolean taskScheduled = new AtomicBoolean(true);
        private final AtomicBoolean terminated = new AtomicBoolean(false);
        private final AtomicReference<ScheduledWork> scheduledWork = new AtomicReference<>();
        private volatile boolean initialized;
        private volatile Disposable live;

        private EventStreamBridge(
                SceneScope scope, long sinceVersion, FluxSink<AgentSceneEventDTO> sink) {
            this.scope = scope;
            this.sinceVersion = sinceVersion;
            this.sink = sink;
            this.deliveredVersion = new AtomicLong(sinceVersion);
            this.highestObservedVersion = new AtomicLong(sinceVersion);
        }

        private void start() {
            live = eventBroker.stream(scope, sinceVersion).subscribe(this::observe, this::fail);
            sink.onDispose(() -> {
                terminated.set(true);
                live.dispose();
                ScheduledWork work = scheduledWork.getAndSet(null);
                if (work != null) {
                    work.finish();
                }
                worker.dispose();
            });
            scheduleReserved(this::initializeAndCatchUp);
        }

        private void observe(AgentSceneEventDTO event) {
            if (terminated.get() || event == null || event.getSceneVersion() == null) {
                return;
            }
            highestObservedVersion.accumulateAndGet(event.getSceneVersion(), Math::max);
            if (initialized) {
                scheduleCatchUp();
            }
        }

        private void initializeAndCatchUp() {
            long currentVersion = currentVersion();
            if (sinceVersion > currentVersion) {
                resync(currentVersion);
                return;
            }
            Long earliestVersion = eventDao.findEarliestSceneVersion(
                    scope.tenantId(), scope.clientId(), scope.sceneId());
            boolean noRetainedBacklog = earliestVersion == null && currentVersion > sinceVersion;
            boolean retainedGap = earliestVersion != null
                    && earliestVersion > 0
                    && sinceVersion < earliestVersion - 1;
            if (noRetainedBacklog || retainedGap) {
                resync(currentVersion());
                return;
            }
            replayThrough(currentVersion);
            initialized = true;
            catchUpObserved();
        }

        private void catchUpObserved() {
            long observedVersion = highestObservedVersion.get();
            if (observedVersion <= deliveredVersion.get() || terminated.get()) {
                return;
            }
            long currentVersion = currentVersion();
            if (observedVersion > currentVersion || deliveredVersion.get() > currentVersion) {
                resync(currentVersion);
                return;
            }
            replayThrough(currentVersion);
        }

        private void replayThrough(long throughVersion) {
            long afterVersion = deliveredVersion.get();
            List<AgentSceneEventDTO> events = readContiguousEvents(
                    scope, afterVersion, throughVersion);
            for (AgentSceneEventDTO source : events) {
                if (terminated.get() || sink.isCancelled()) {
                    return;
                }
                AgentSceneEventDTO event = AgentSceneEventBroker.copyEvent(source);
                long expectedVersion = deliveredVersion.get() + 1;
                if (event == null || event.getSceneVersion() == null
                        || event.getSceneVersion() != expectedVersion) {
                    throw new ContinuityGapException();
                }
                sink.next(event);
                deliveredVersion.set(expectedVersion);
            }
            if (!terminated.get() && deliveredVersion.get() != throughVersion) {
                throw new ContinuityGapException();
            }
        }

        private long currentVersion() {
            return versionOrZero(eventDao.findCurrentSceneVersion(
                    scope.tenantId(), scope.clientId(), scope.sceneId()));
        }

        private void scheduleCatchUp() {
            if (terminated.get() || sink.isCancelled()
                    || highestObservedVersion.get() <= deliveredVersion.get()
                    || !taskScheduled.compareAndSet(false, true)) {
                return;
            }
            scheduleReserved(this::catchUpObserved);
        }

        private void scheduleReserved(Runnable action) {
            ScheduledWork work = new ScheduledWork();
            scheduledWork.set(work);
            try {
                worker.schedule(() -> runScheduled(action, work));
            } catch (RuntimeException error) {
                scheduledWork.compareAndSet(work, null);
                work.finish();
                taskScheduled.set(false);
                fail(error);
            }
        }

        private void runScheduled(Runnable action, ScheduledWork work) {
            try {
                if (!terminated.get() && !sink.isCancelled()) {
                    action.run();
                }
            } catch (ContinuityGapException gap) {
                try {
                    resync(currentVersion());
                } catch (RuntimeException error) {
                    fail(error);
                }
            } catch (RuntimeException error) {
                fail(error);
            } finally {
                taskScheduled.set(false);
                scheduledWork.compareAndSet(work, null);
                work.finish();
                if (initialized) {
                    scheduleCatchUp();
                }
            }
        }

        private void resync(long currentVersion) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            if (live != null) {
                live.dispose();
            }
            if (!sink.isCancelled()) {
                sink.next(resyncRequired(currentVersion));
                sink.complete();
            }
        }

        private void fail(Throwable error) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            if (live != null) {
                live.dispose();
            }
            if (!sink.isCancelled()) {
                sink.error(error);
            }
        }

        private final class ScheduledWork {
            private final AtomicBoolean finished = new AtomicBoolean(false);

            private ScheduledWork() {
                scheduledEventBridgeTasks.incrementAndGet();
            }

            private void finish() {
                if (finished.compareAndSet(false, true)) {
                    scheduledEventBridgeTasks.decrementAndGet();
                }
            }
        }
    }

    private static final class ContinuityGapException extends RuntimeException {
        private ContinuityGapException() {
            super(null, null, false, false);
        }
    }

    private void publishAfterCommit(SceneScope scope, AgentSceneEventDTO source) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Direct unit calls and nontransactional misuse remain durable-only; production
            // @Transactional invocation registers the live publication below.
            return;
        }
        AgentSceneEventDTO event = AgentSceneEventBroker.copyEvent(source);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventBroker.publish(scope, event);
            }
        });
    }

    private static long versionOrZero(Long version) {
        return version == null ? 0L : Math.max(0L, version);
    }

    private void rejectPersonaConflict(
            SceneScope scope,
            AgentSceneStateDTO requested,
            Map<String, AgentRuntimeEntity> realAgents,
            long now) {
        for (AgentSceneStateEntity existing : safeList(stateDao.findActiveByScene(
                scope.tenantId(), scope.clientId(), scope.sceneId(), now))) {
            if (!isActive(existing, now)
                    || requested.getAgentId().equals(existing.getAgentId())
                    || !requested.getPersonaCode().equals(existing.getPersonaCode())) {
                continue;
            }
            AgentRuntimeEntity realAgent = realAgents.get(existing.getAgentId());
            if (realAgent != null && requested.getPersonaCode().equals(realAgent.getPersonaCode())) {
                throw new IllegalArgumentException("personaCode is already active for another agent in this scene");
            }
        }
    }

    private List<AgentRuntimeEntity> scopedRoster(SceneScope scope) {
        return safeList(runtimeDao.findRosterByOwner(scope.clientId(), scope.tenantId(), null, null)).stream()
                .filter(runtime -> runtime != null
                        && scope.clientId().equals(runtime.getClientId())
                        && scope.tenantId().equals(runtime.getOwnerJiacn()))
                .toList();
    }

    private Map<String, AgentRuntimeEntity> indexRealAgents(List<AgentRuntimeEntity> roster) {
        Map<String, AgentRuntimeEntity> agents = new HashMap<>();
        for (AgentRuntimeEntity runtime : roster) {
            if (!StringUtil.isBlank(runtime.getAgentId()) && !StringUtil.isBlank(runtime.getPersonaCode())) {
                agents.put(runtime.getAgentId(), runtime);
            }
        }
        return agents;
    }

    private AgentScenePhaseReportDTO requirePhaseReport(AgentScenePhaseReportDTO source) {
        if (source == null) {
            throw new IllegalArgumentException("phase report is required");
        }
        AgentScenePhaseReportDTO report = new AgentScenePhaseReportDTO();
        report.setReportId(trim(source.getReportId()));
        report.setAgentId(trim(source.getAgentId()));
        report.setStateVersion(source.getStateVersion());
        report.setPhase(trim(source.getPhase()));
        report.setRegionId(trim(source.getRegionId()));
        report.setOccurredAt(source.getOccurredAt());
        requireText(report.getReportId(), "reportId");
        requireText(report.getAgentId(), "agentId");
        requireText(report.getRegionId(), "regionId");
        if (!AgentSceneConstants.PHASES.contains(report.getPhase())) {
            throw new IllegalArgumentException("phase must be arrived or blocked");
        }
        if (report.getStateVersion() == null || report.getStateVersion() <= 0) {
            throw new IllegalArgumentException("stateVersion must be positive");
        }
        if (report.getOccurredAt() == null || report.getOccurredAt() < 0) {
            throw new IllegalArgumentException("occurredAt is required and must be nonnegative");
        }
        return report;
    }

    private static boolean phaseMatchesCurrent(
            AgentScenePhaseReportDTO report, AgentSceneStateEntity current) {
        return current != null
                && report.getAgentId().equals(current.getAgentId())
                && report.getStateVersion().equals(current.getStateVersion())
                && report.getRegionId().equals(current.getTargetRegionId())
                && (current.getStartedAt() == null || report.getOccurredAt() >= current.getStartedAt());
    }

    private static AgentScenePhaseReportEntity toPhaseReportEntity(
            AgentScenePhaseReportDTO report, String result, long processedAt) {
        AgentScenePhaseReportEntity entity = new AgentScenePhaseReportEntity();
        entity.setReportId(report.getReportId());
        entity.setAgentId(report.getAgentId());
        entity.setStateVersion(report.getStateVersion());
        entity.setPhase(report.getPhase());
        entity.setRegionId(report.getRegionId());
        entity.setResult(result);
        entity.setOccurredAt(report.getOccurredAt());
        entity.setProcessedAt(processedAt);
        return entity;
    }

    private static AgentScenePhaseResultDTO phaseResult(
            String reportId, Long stateVersion, String result) {
        AgentScenePhaseResultDTO response = new AgentScenePhaseResultDTO();
        response.setReportId(reportId);
        response.setStateVersion(stateVersion);
        response.setResult(result);
        return response;
    }

    private AgentSceneStateDTO requireState(AgentSceneStateDTO source) {
        if (source == null) {
            throw new IllegalArgumentException("scene state is required");
        }
        AgentSceneStateDTO state = AgentSceneStateDTO.copyOf(source);
        requireText(state.getAgentId(), "agentId");
        requireText(state.getPersonaCode(), "personaCode");
        requireText(state.getBehavior(), "behavior");
        requireText(state.getTargetRegionId(), "targetRegionId");
        requireText(state.getPhase(), "phase");
        if (state.getStartedAt() == null || state.getStartedAt() < 0) {
            throw new IllegalArgumentException("startedAt is required and must be nonnegative");
        }
        if (state.getExpectedArrivalAt() != null) {
            if (state.getExpectedArrivalAt() < 0) {
                throw new IllegalArgumentException("expectedArrivalAt must be nonnegative");
            }
            if (state.getExpectedArrivalAt() < state.getStartedAt()) {
                throw new IllegalArgumentException("expectedArrivalAt cannot be before startedAt");
            }
        }
        if (state.getExpiresAt() != null) {
            if (state.getExpiresAt() < 0) {
                throw new IllegalArgumentException("expiresAt must be nonnegative");
            }
            if (state.getExpiresAt() < state.getStartedAt()) {
                throw new IllegalArgumentException("expiresAt cannot be before startedAt");
            }
        }
        if (state.getExpectedArrivalAt() != null && state.getExpiresAt() != null
                && state.getExpiresAt() < state.getExpectedArrivalAt()) {
            throw new IllegalArgumentException("expiresAt cannot be before expectedArrivalAt");
        }
        return state;
    }

    private SceneScope requireScope(String sceneId) {
        EsContext context = EsContextHolder.getContext();
        String tenantId = trim(context.getJiacn());
        String clientId = trim(context.getClientId());
        String normalizedSceneId = trim(sceneId);
        if (StringUtil.isBlank(tenantId)
                || StringUtil.isBlank(clientId)
                || StringUtil.isBlank(normalizedSceneId)) {
            throw new IllegalArgumentException("tenant jiacn, clientId and sceneId are required");
        }
        return new SceneScope(tenantId, clientId, normalizedSceneId);
    }

    private void requireText(String value, String field) {
        if (StringUtil.isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static boolean stateMatchesVisibleAgent(
            AgentSceneStateEntity state,
            Map<String, AgentRuntimeEntity> visibleByAgent) {
        AgentRuntimeEntity runtime = visibleByAgent.get(state.getAgentId());
        return runtime != null && runtime.getPersonaCode().equals(state.getPersonaCode());
    }

    private static boolean isActive(AgentSceneStateEntity state, long now) {
        return state != null && (state.getExpiresAt() == null || state.getExpiresAt() > now);
    }

    private static AgentSceneAgentDTO toAgentDTO(AgentRuntimeEntity runtime) {
        AgentSceneAgentDTO agent = new AgentSceneAgentDTO();
        agent.setAgentId(runtime.getAgentId());
        agent.setPersonaCode(runtime.getPersonaCode());
        agent.setStatus(runtime.getStatus());
        return agent;
    }

    private static AgentSceneStateEntity toStateEntity(AgentSceneStateDTO state, long stateVersion) {
        AgentSceneStateEntity entity = new AgentSceneStateEntity();
        entity.setAgentId(state.getAgentId());
        entity.setPersonaCode(state.getPersonaCode());
        entity.setBehavior(state.getBehavior());
        entity.setOriginRegionId(state.getOriginRegionId());
        entity.setTargetRegionId(state.getTargetRegionId());
        entity.setRelatedType(state.getRelatedType());
        entity.setRelatedId(state.getRelatedId());
        entity.setPhase(state.getPhase());
        entity.setStateVersion(stateVersion);
        entity.setStartedAt(state.getStartedAt());
        entity.setExpectedArrivalAt(state.getExpectedArrivalAt());
        entity.setExpiresAt(state.getExpiresAt());
        return entity;
    }

    private static AgentSceneStateDTO toStateDTO(AgentSceneStateEntity entity) {
        AgentSceneStateDTO state = new AgentSceneStateDTO();
        state.setAgentId(entity.getAgentId());
        state.setPersonaCode(entity.getPersonaCode());
        state.setBehavior(entity.getBehavior());
        state.setOriginRegionId(entity.getOriginRegionId());
        state.setTargetRegionId(entity.getTargetRegionId());
        state.setRelatedType(entity.getRelatedType());
        state.setRelatedId(entity.getRelatedId());
        state.setPhase(entity.getPhase());
        state.setStateVersion(entity.getStateVersion());
        state.setStartedAt(entity.getStartedAt());
        state.setExpectedArrivalAt(entity.getExpectedArrivalAt());
        state.setExpiresAt(entity.getExpiresAt());
        return state;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static <T> List<T> safeList(List<T> source) {
        return source == null ? List.of() : source;
    }

}
