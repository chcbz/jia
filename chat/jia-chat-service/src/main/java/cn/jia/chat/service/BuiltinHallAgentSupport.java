package cn.jia.chat.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentStatusDTO;
import cn.jia.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BuiltinHallAgentSupport implements ApplicationRunner {
    public static final String SONGJIANG_AGENT_ID = AgentConstants.BUILTIN_SONGJIANG_AGENT_ID;
    public static final String SONGJIANG_NAME = "宋江";
    public static final String SONGJIANG_ENDPOINT = "builtin://juyiting/songjiang";
    private static final List<String> SONGJIANG_ABILITIES = List.of("coordination", "dispatch", "planning", "briefing");

    private final AgentService agentService;

    @Override
    public void run(ApplicationArguments args) {
        ensureSongJiangRuntime();
    }

    public boolean isBuiltinAgent(String agentId) {
        return SONGJIANG_AGENT_ID.equals(agentId);
    }

    public String defaultAgentId() {
        return SONGJIANG_AGENT_ID;
    }

    public AgentRuntimeDTO defaultAgent() {
        ensureSongJiangRuntime();
        try {
            return agentService.get(SONGJIANG_AGENT_ID);
        } catch (Exception ignored) {
            return buildDefaultRuntimeDto();
        }
    }

    private void ensureSongJiangRuntime() {
        try {
            AgentRuntimeDTO existing = agentService.get(SONGJIANG_AGENT_ID);
            if (existing != null && AgentConstants.STATUS_ONLINE.equals(existing.getStatus())) {
                return;
            }
            AgentStatusDTO request = new AgentStatusDTO();
            request.setStatus(AgentConstants.STATUS_ONLINE);
            agentService.updateStatus(SONGJIANG_AGENT_ID, request);
            return;
        } catch (Exception ignored) {
            // register below
        }
        AgentRegisterDTO request = new AgentRegisterDTO();
        request.setAgentId(SONGJIANG_AGENT_ID);
        request.setName(SONGJIANG_NAME);
        request.setPersonaName(SONGJIANG_NAME);
        request.setAbilities(SONGJIANG_ABILITIES);
        request.setEndpoint(SONGJIANG_ENDPOINT);
        agentService.register(request);
    }

    private AgentRuntimeDTO buildDefaultRuntimeDto() {
        AgentRuntimeDTO dto = new AgentRuntimeDTO();
        dto.setAgentId(SONGJIANG_AGENT_ID);
        dto.setName(SONGJIANG_NAME);
        dto.setPersonaName(SONGJIANG_NAME);
        dto.setAbilities(SONGJIANG_ABILITIES);
        dto.setEndpoint(SONGJIANG_ENDPOINT);
        dto.setStatus(AgentConstants.STATUS_ONLINE);
        dto.setLastSeenAt(System.currentTimeMillis());
        return dto;
    }

}
