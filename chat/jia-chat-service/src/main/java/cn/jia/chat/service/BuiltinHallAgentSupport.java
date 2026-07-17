package cn.jia.chat.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BuiltinHallAgentSupport {
    public static final String SONGJIANG_AGENT_ID = AgentConstants.BUILTIN_SONGJIANG_AGENT_ID;
    public static final String SONGJIANG_NAME = "宋江";
    public static final String SONGJIANG_ENDPOINT = "builtin://juyiting/songjiang";
    private static final List<String> SONGJIANG_ABILITIES = List.of("coordination", "dispatch", "planning", "briefing", "task_management");

    private final AgentService agentService;

    public boolean isBuiltinAgent(String agentId) {
        return SONGJIANG_AGENT_ID.equals(agentId);
    }

    public String defaultAgentId() {
        return SONGJIANG_AGENT_ID;
    }

    public AgentRuntimeDTO defaultAgent() {
        try {
            return applyBuiltinIdentity(agentService.get(SONGJIANG_AGENT_ID));
        } catch (Exception ignored) {
            return buildDefaultRuntimeDto();
        }
    }

    private AgentRuntimeDTO buildDefaultRuntimeDto() {
        return applyBuiltinIdentity(new AgentRuntimeDTO());
    }

    private AgentRuntimeDTO applyBuiltinIdentity(AgentRuntimeDTO dto) {
        if (dto == null) {
            dto = new AgentRuntimeDTO();
        }
        dto.setAgentId(SONGJIANG_AGENT_ID);
        dto.setName(SONGJIANG_NAME);
        dto.setPersonaCode(AgentConstants.BUILTIN_SONGJIANG_PERSONA_CODE);
        dto.setPersonaName(SONGJIANG_NAME);
        dto.setSystemAgent(true);
        dto.setBound(true);
        dto.setBoundToMe(false);
        dto.setCanBind(false);
        dto.setCanOperate(false);
        if (dto.getAbilities() == null || dto.getAbilities().isEmpty()) {
            dto.setAbilities(SONGJIANG_ABILITIES);
        }
        dto.setEndpoint(SONGJIANG_ENDPOINT);
        dto.setStatus(AgentConstants.STATUS_ONLINE);
        if (dto.getLastSeenAt() == null) {
            dto.setLastSeenAt(System.currentTimeMillis());
        }
        return dto;
    }

}
