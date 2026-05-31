package cn.jia.agent.event;

import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskDTO;

public interface AgentEventPublisher {
    default void publishAgentStatus(AgentRuntimeDTO agent) {
    }

    default void publishTaskEvent(String eventType, AgentTaskDTO task) {
    }
}
