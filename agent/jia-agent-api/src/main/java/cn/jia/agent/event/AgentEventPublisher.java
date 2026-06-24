package cn.jia.agent.event;

import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentCapabilityDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskDTO;

import java.util.List;
import java.util.Set;

public interface AgentEventPublisher {
    default void publishAgentStatus(AgentRuntimeDTO agent) {
    }

    default void publishTaskEvent(String eventType, AgentTaskDTO task) {
    }

    default void publishCapabilityIndex(List<AgentCapabilityDTO> capabilities) {
    }

    default Set<String> connectedAgentIds() {
        return Set.of();
    }

    default AgentActionDispatchResultDTO publishAgentAction(AgentActionIntentDTO intent) {
        AgentActionDispatchResultDTO result = new AgentActionDispatchResultDTO();
        result.setIntentId(intent.getIntentId());
        result.setTaskId(intent.getTaskId());
        result.setTargetAgentId(intent.getActorAgentId());
        result.setStatus("queued");
        result.setMessage("Agent action publisher unavailable");
        return result;
    }
}
