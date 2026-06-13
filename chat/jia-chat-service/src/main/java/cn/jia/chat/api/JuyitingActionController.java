package cn.jia.chat.api;

import cn.jia.chat.service.HallActionDispatchResult;
import cn.jia.chat.service.HallActionDispatcher;
import cn.jia.chat.service.HallActionIntent;
import cn.jia.chat.service.HallAgentMailboxItem;
import cn.jia.core.entity.JsonResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/juyiting")
@RequiredArgsConstructor
public class JuyitingActionController {
    private final HallActionDispatcher hallActionDispatcher;

    @PostMapping("/actions/{intentId}/dispatch")
    public JsonResult<HallActionDispatchResult> dispatch(@PathVariable String intentId,
            @RequestBody HallActionIntent request) {
        request.setIntentId(intentId);
        return JsonResult.success(hallActionDispatcher.dispatch(request));
    }

    @GetMapping("/agents/{agentId}/mailbox")
    public JsonResult<List<HallAgentMailboxItem>> mailbox(@PathVariable String agentId) {
        return JsonResult.success(hallActionDispatcher.mailbox(agentId));
    }
}
