package cn.jia.chat.service;

import cn.jia.chat.entity.AgentTaskThreadDTO;
import cn.jia.chat.entity.AgentTaskThreadMessageCreateDTO;
import cn.jia.chat.entity.AgentTaskThreadMessageDTO;

import java.util.List;

public interface AgentTaskThreadService {
    AgentTaskThreadDTO getOrCreateTeamThread(
            String tenantId, String clientId, String taskId, String actorAgentId, String title);

    AgentTaskThreadDTO getTeamThread(
            String tenantId, String clientId, String taskId, String actorAgentId);

    AgentTaskThreadMessageDTO appendTeamMessage(
            String tenantId, String clientId, String taskId,
            AgentTaskThreadMessageCreateDTO request);

    List<AgentTaskThreadMessageDTO> listTeamMessages(
            String tenantId, String clientId, String taskId, String actorAgentId, int limit);
}
