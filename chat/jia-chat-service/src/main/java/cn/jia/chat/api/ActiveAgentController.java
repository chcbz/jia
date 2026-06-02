package cn.jia.chat.api;

import cn.jia.chat.handler.OpenClawChannelWebSocketHandler;
import cn.jia.core.entity.JsonResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class ActiveAgentController {
    private final OpenClawChannelWebSocketHandler openClawChannelWebSocketHandler;

    @GetMapping("/active")
    public Object activeAgents() {
        return JsonResult.success(openClawChannelWebSocketHandler.getConnectedAgents());
    }
}
