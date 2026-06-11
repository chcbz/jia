package cn.jia.agent.service;

import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskReportDTO;
import cn.jia.agent.entity.AgentTaskSearchDTO;
import cn.jia.agent.entity.DialogueRequestDTO;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface AgentService {
    AgentRegisterResultDTO register(AgentRegisterDTO request);

    PageInfo<AgentRuntimeDTO> list(String status, String ability, int pageNum, int pageSize);

    PageInfo<AgentRuntimeDTO> listRoster(String status, String ability, int pageNum, int pageSize);

    List<AgentRuntimeDTO> listMapAgents();

    AgentRuntimeDTO get(String agentId);

    AgentRuntimeDTO updateStatus(String agentId, AgentStatusDTO request);

    List<AgentTaskDTO> getAgentTasks(String agentId);

    List<AgentPersonaEntity> listPersonas();

    AgentPersonaEntity getPersona(String name);

    String generateDialogue(DialogueRequestDTO request);

    AgentRuntimeDTO getStats(String agentId);

    PageInfo<AgentTaskDTO> searchTasks(AgentTaskSearchDTO request);

    AgentTaskDTO assignTask(String taskId, AgentTaskAssignDTO request);

    AgentTaskDTO reportTask(String taskId, AgentTaskReportDTO request);
}
