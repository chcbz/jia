package cn.jia.agent.event;

import cn.jia.agent.entity.AgentActionDispatchResultDTO;
import cn.jia.agent.entity.AgentActionIntentDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskDTO;

public interface AgentEventPublisher {
    default void publishAgentStatus(AgentRuntimeDTO agent) {
    }

    default void publishTaskEvent(String eventType, AgentTaskDTO task) {
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
