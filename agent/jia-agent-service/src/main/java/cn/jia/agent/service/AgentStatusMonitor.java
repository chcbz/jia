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
        syncWebSocketAgentStatuses();

        long cutoffTime = System.currentTimeMillis() - heartbeatTimeoutSeconds * 1000;
        for (AgentRuntimeEntity agent : agentRuntimeDao.findHeartbeatTimedOut(cutoffTime)) {
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

    private void syncWebSocketAgentStatuses() {
        AgentEventPublisher publisher = eventPublisherProvider.getIfAvailable();
        if (publisher == null) {
            return;
        }

        Set<String> connectedAgentIds = Optional.ofNullable(publisher.connectedAgentIds()).orElseGet(Set::of);
        long now = System.currentTimeMillis();
        List<AgentRuntimeEntity> agents = agentRuntimeDao.findByStatusAndAbility(null, null);
        for (AgentRuntimeEntity agent : agents) {
            if (AgentConstants.BUILTIN_SONGJIANG_AGENT_ID.equals(agent.getAgentId())) {
                continue;
            }

            boolean connected = connectedAgentIds.contains(agent.getAgentId());
            String previousStatus = agent.getStatus();
            String nextStatus = resolveStatus(agent, connected);
            boolean statusChanged = !nextStatus.equals(previousStatus);

            if (connected) {
                agent.setLastSeenAt(now);
            } else if (!AgentConstants.STATUS_OFFLINE.equals(previousStatus)) {
                agent.setCurrentTaskId(null);
                agent.setCurrentTaskTitle(null);
                agent.setErrorMessage(null);
            }

            if (statusChanged) {
                log.debug("Syncing agent status from WebSocket connection: agentId={}, connected={}, {} -> {}",
                        agent.getAgentId(), connected, previousStatus, nextStatus);
                agent.setStatus(nextStatus);
            }

            if (connected || statusChanged) {
                agentRuntimeDao.updateById(agent);
            }
            if (statusChanged) {
                publishAgentStatus(agent);
            }
        }
    }

    private String resolveStatus(AgentRuntimeEntity agent, boolean connected) {
        if (!connected) {
            return AgentConstants.STATUS_OFFLINE;
        }
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
