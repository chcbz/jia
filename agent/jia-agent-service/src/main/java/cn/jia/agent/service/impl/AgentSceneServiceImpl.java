package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentSceneEventDao;
import cn.jia.agent.dao.AgentSceneStateDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentSceneAgentDTO;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Named
public class AgentSceneServiceImpl implements AgentSceneService {
    private static final String EVENT_STATE_UPDATED = "agent-scene-state-updated";
    private static final String EVENT_RESYNC_REQUIRED = "resync-required";
    private static final int BACKLOG_PAGE_SIZE = 1000;
    private static final Set<String> VISIBLE_STATUSES = Set.of(
            AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY);

    private final AgentSceneStateDao stateDao;
    private final AgentSceneEventDao eventDao;
    private final AgentRuntimeDao runtimeDao;
    private final AgentSceneEventBroker eventBroker;

    @Inject
    public AgentSceneServiceImpl(
            AgentSceneStateDao stateDao,
            AgentSceneEventDao eventDao,
            AgentRuntimeDao runtimeDao,
            AgentSceneEventBroker eventBroker) {
        this.stateDao = stateDao;
        this.eventDao = eventDao;
        this.runtimeDao = runtimeDao;
        this.eventBroker = eventBroker;
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
    public AgentScenePhaseResultDTO reportPhase(String sceneId, AgentScenePhaseReportDTO request) {
        requireScope(sceneId);
        throw new UnsupportedOperationException("Agent scene phase reporting is implemented in Task 5");
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
        Object monitor = new Object();
        List<AgentSceneEventDTO> pendingLive = new ArrayList<>();
        AtomicLong deliveredVersion = new AtomicLong(sinceVersion);
        AtomicBoolean backlogComplete = new AtomicBoolean(false);
        Disposable live = eventBroker.stream(scope, sinceVersion).subscribe(
                event -> {
                    synchronized (monitor) {
                        if (!backlogComplete.get()) {
                            pendingLive.add(AgentSceneEventBroker.copyEvent(event));
                        } else {
                            emitIfNew(sink, deliveredVersion, event);
                        }
                    }
                }, sink::error);
        sink.onDispose(live::dispose);

        try {
            long currentVersion = versionOrZero(eventDao.findCurrentSceneVersion(
                    scope.tenantId(), scope.clientId(), scope.sceneId()));
            Long earliestVersion = eventDao.findEarliestSceneVersion(
                    scope.tenantId(), scope.clientId(), scope.sceneId());
            boolean noRetainedBacklog = earliestVersion == null && currentVersion > sinceVersion;
            boolean retainedGap = earliestVersion != null
                    && earliestVersion > 0
                    && sinceVersion < earliestVersion - 1;
            if (noRetainedBacklog || retainedGap) {
                synchronized (monitor) {
                    backlogComplete.set(true);
                    pendingLive.clear();
                    sink.next(resyncRequired(currentVersion));
                    sink.complete();
                }
                return;
            }

            List<AgentSceneEventDTO> backlog = readBacklog(scope, sinceVersion, currentVersion);
            synchronized (monitor) {
                for (AgentSceneEventDTO event : backlog) {
                    emitIfNew(sink, deliveredVersion, event);
                }
                pendingLive.sort(Comparator.comparing(AgentSceneEventDTO::getSceneVersion));
                for (AgentSceneEventDTO event : pendingLive) {
                    emitIfNew(sink, deliveredVersion, event);
                }
                pendingLive.clear();
                backlogComplete.set(true);
            }
        } catch (RuntimeException error) {
            live.dispose();
            sink.error(error);
        }
    }

    private List<AgentSceneEventDTO> readBacklog(
            SceneScope scope, long sinceVersion, long handoffVersion) {
        if (handoffVersion <= sinceVersion) {
            return List.of();
        }
        List<AgentSceneEventDTO> backlog = new ArrayList<>();
        long cursor = sinceVersion;
        while (cursor < handoffVersion) {
            List<AgentSceneEventEntity> page = safeList(eventDao.findAfterVersion(
                    scope.tenantId(), scope.clientId(), scope.sceneId(), cursor, BACKLOG_PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            long priorCursor = cursor;
            for (AgentSceneEventEntity entity : page) {
                if (entity == null || entity.getSceneVersion() == null
                        || entity.getSceneVersion() <= cursor) {
                    continue;
                }
                if (entity.getSceneVersion() > handoffVersion) {
                    break;
                }
                AgentSceneEventDTO event = toEventDTO(entity);
                backlog.add(event);
                cursor = entity.getSceneVersion();
            }
            if (page.size() < BACKLOG_PAGE_SIZE || cursor == priorCursor) {
                break;
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

    private static void emitIfNew(
            FluxSink<AgentSceneEventDTO> sink,
            AtomicLong deliveredVersion,
            AgentSceneEventDTO source) {
        AgentSceneEventDTO event = AgentSceneEventBroker.copyEvent(source);
        if (event == null || event.getSceneVersion() == null
                || event.getSceneVersion() <= deliveredVersion.get()
                || sink.isCancelled()) {
            return;
        }
        deliveredVersion.set(event.getSceneVersion());
        sink.next(event);
    }

    private static AgentSceneEventDTO resyncRequired(long currentVersion) {
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(currentVersion);
        event.setEventType(EVENT_RESYNC_REQUIRED);
        return event;
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
