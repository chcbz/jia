package cn.jia.chat.api;

import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.core.entity.JsonResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class ActiveAgentController {
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final BuiltinHallAgentSupport builtinHallAgentSupport;

    @GetMapping("/active")
    public Object activeAgents() {
        List<AgentRuntimeDTO> agents = new ArrayList<>(agentWebSocketHandler.getConnectedAgents());
        boolean hasSongJiang = agents.stream()
                .anyMatch(agent -> BuiltinHallAgentSupport.SONGJIANG_AGENT_ID.equals(agent.getAgentId()));
        if (!hasSongJiang) {
            agents.addFirst(builtinHallAgentSupport.defaultAgent());
        }
        return JsonResult.success(agents);
    }
}
