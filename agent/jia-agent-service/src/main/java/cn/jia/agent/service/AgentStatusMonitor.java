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

import java.util.Optional;

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
