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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStatusMonitor {
    private final AgentRuntimeDao agentRuntimeDao;
    private final ObjectProvider<AgentEventPublisher> eventPublisherProvider;

    @Value("${jia.agent.status.heartbeat-timeout-seconds:60}")
    private long heartbeatTimeoutSeconds;

    @Scheduled(fixedDelayString = "${jia.agent.status.offline-scan-interval-seconds:10}000")
    @Transactional(rollbackFor = Exception.class)
    public void markHeartbeatTimedOutAgentsOffline() {
        Set<String> connectedAgentIds = connectedAgentIds();
        syncWebSocketAgentStatuses(connectedAgentIds);

        long cutoffTime = System.currentTimeMillis() - heartbeatTimeoutSeconds * 1000;
        for (AgentRuntimeEntity agent : agentRuntimeDao.findHeartbeatTimedOut(cutoffTime)) {
            // A live local WebSocket is stronger evidence than an asynchronously persisted
            // heartbeat. Never turn a socket-owning agent offline from a stale DB snapshot.
            if (connectedAgentIds.contains(agent.getAgentId())) {
                continue;
            }
            if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agent.getAgentId())) {
                continue;
            }
            log.debug("Marking heartbeat timed out agent offline: {}", agent.getAgentId());
            agent.setStatus(AgentConstants.STATUS_OFFLINE);
            agent.setCurrentTaskId(null);
            agent.setCurrentTaskTitle(null);
            agent.setErrorMessage(null);
            agentRuntimeDao.updateById(agent);
            publishAgentStatus(agent);
        }
    }

    private void syncWebSocketAgentStatuses(Set<String> connectedAgentIds) {
        long now = System.currentTimeMillis();
        List<AgentRuntimeEntity> agents = agentRuntimeDao.findByStatusAndAbility(null, null);
        for (AgentRuntimeEntity agent : agents) {
            if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agent.getAgentId())) {
                continue;
            }

            // The WebSocket registry is local to this JVM.  Absence from this instance
            // must not be interpreted as a disconnect in a multi-instance deployment:
            // another instance may own the socket and refresh lastSeenAt instead.
            if (!connectedAgentIds.contains(agent.getAgentId())) {
                continue;
            }

            String previousStatus = agent.getStatus();
            String nextStatus = resolveConnectedStatus(agent);
            boolean statusChanged = !nextStatus.equals(previousStatus);

            agent.setLastSeenAt(now);
            if (statusChanged) {
                log.debug("Refreshing locally connected agent status: agentId={}, {} -> {}",
                        agent.getAgentId(), previousStatus, nextStatus);
                agent.setStatus(nextStatus);
            }

            agentRuntimeDao.updateById(agent);
            if (statusChanged) {
                publishAgentStatus(agent);
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

    private String resolveConnectedStatus(AgentRuntimeEntity agent) {
        if (hasText(agent.getCurrentTaskId())) {
            return AgentConstants.STATUS_BUSY;
        }
        return AgentConstants.STATUS_ONLINE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void publishAgentStatus(AgentRuntimeEntity entity) {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(entity.getAgentId());
        dto.setName(entity.getName());
        dto.setAvatar(entity.getAvatar());
        dto.setPersonaName(entity.getPersonaName());
        dto.setStatus(entity.getStatus());
        dto.setEndpoint(entity.getEndpoint());
        dto.setLastSeenAt(entity.getLastSeenAt());
        dto.setErrorMessage(entity.getErrorMessage());
        Optional.ofNullable(eventPublisherProvider.getIfAvailable())
                .ifPresent(publisher -> publisher.publishAgentStatus(dto));
    }
}
