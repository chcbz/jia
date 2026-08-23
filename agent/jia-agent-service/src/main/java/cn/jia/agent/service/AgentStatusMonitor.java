package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.event.AgentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStatusMonitor {
    private final AgentRuntimeDao agentRuntimeDao;
    private final AgentStatusTransitionWorker transitionWorker;
    private final AgentScopePublicationCoordinator scopePublicationCoordinator;
    private final ObjectProvider<AgentEventPublisher> eventPublisherProvider;

    @Value("${jia.agent.status.heartbeat-timeout-seconds:60}")
    private long heartbeatTimeoutSeconds;

    @Scheduled(fixedDelayString = "${jia.agent.status.offline-scan-interval-seconds:10}000")
    public void markHeartbeatTimedOutAgentsOffline() {
        Set<String> connectedAgentIds = connectedAgentIds();
        syncWebSocketAgentStatuses(connectedAgentIds);

        long cutoffTime = System.currentTimeMillis() - heartbeatTimeoutSeconds * 1000;
        for (AgentRuntimeEntity candidate : agentRuntimeDao.findHeartbeatTimedOut(cutoffTime)) {
            if (connectedAgentIds.contains(candidate.getAgentId())
                    || AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(candidate.getAgentId())) {
                continue;
            }
            try {
                AgentStatusTransitionWorker.Transition transition =
                        transitionWorker.markHeartbeatTimedOutOffline(candidate, cutoffTime);
                if (transition != null && transition.statusChanged()) {
                    log.debug("Marked heartbeat timed out agent offline: {}",
                            transition.agentId());
                    publishAgentStatus(transition);
                }
            } catch (RuntimeException failure) {
                log.warn("Skipping failed heartbeat timeout transition: agentId={}, failureType={}",
                        candidate.getAgentId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void syncWebSocketAgentStatuses(Set<String> connectedAgentIds) {
        long now = System.currentTimeMillis();
        List<AgentRuntimeEntity> candidates = agentRuntimeDao.findByStatusAndAbility(null, null);
        for (AgentRuntimeEntity candidate : candidates) {
            if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(candidate.getAgentId())
                    || !connectedAgentIds.contains(candidate.getAgentId())) {
                continue;
            }
            try {
                AgentStatusTransitionWorker.Transition transition =
                        transitionWorker.refreshConnected(candidate, now);
                if (transition != null && transition.statusChanged()) {
                    log.debug("Refreshed locally connected agent status: agentId={}, status={}",
                            transition.agentId(), transition.status());
                    publishAgentStatus(transition);
                }
            } catch (RuntimeException failure) {
                log.warn("Skipping failed connected Agent refresh: agentId={}, failureType={}",
                        candidate.getAgentId(), failure.getClass().getSimpleName());
            }
        }
    }

    private Set<String> connectedAgentIds() {
        AgentEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (publisher == null) {
            return Set.of();
        }
        return Optional.ofNullable(publisher.connectedAgentIds()).orElseGet(Set::of);
    }

    private void publishAgentStatus(AgentStatusTransitionWorker.Transition transition) {
        scopePublicationCoordinator.execute(
                transition.clientId(), transition.ownerJiacn(), () -> {
                    AgentEventPublisher publisher = eventPublisherProvider.getIfAvailable();
                    if (publisher == null) {
                        return;
                    }
                    AgentRuntimeEntity current =
                            transitionWorker.revalidateForPublication(transition);
                    if (current == null) {
                        log.debug("Skipping stale Agent monitor publication: agentId={}, status={}, lastSeenAt={}",
                                transition.agentId(), transition.status(), transition.lastSeenAt());
                        return;
                    }
                    publisher.publishAgentStatus(
                            transition.clientId(), transition.ownerJiacn(), toRuntimeDTO(current));
                });
    }

    private AgentRuntimeDTO toRuntimeDTO(AgentRuntimeEntity entity) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(entity.getAgentId());
        dto.setName(entity.getName());
        dto.setAvatar(entity.getAvatar());
        dto.setPersonaName(entity.getPersonaName());
        dto.setStatus(entity.getStatus());
        dto.setCurrentTaskId(entity.getCurrentTaskId());
        dto.setCurrentTaskTitle(entity.getCurrentTaskTitle());
        dto.setEndpoint(entity.getEndpoint());
        dto.setLastSeenAt(entity.getLastSeenAt());
        dto.setErrorMessage(entity.getErrorMessage());
        return dto;
    }

}
