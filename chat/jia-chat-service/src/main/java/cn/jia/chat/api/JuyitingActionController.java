package cn.jia.chat.api;

import cn.jia.chat.service.HallActionDispatchResult;
import cn.jia.chat.service.HallActionDispatcher;
import cn.jia.chat.service.HallActionIntent;
import cn.jia.chat.service.HallTrustedCaller;
import cn.jia.core.entity.JsonResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/juyiting")
@RequiredArgsConstructor
public class JuyitingActionController {
    private final HallActionDispatcher hallActionDispatcher;

    @PostMapping("/actions/{intentId}/dispatch")
    public JsonResult<HallActionDispatchResult> dispatch(
            @PathVariable String intentId,
            @RequestParam(required = false) String callerAgentId,
            @RequestBody HallActionIntent request,
            Authentication authentication) {
        if (request == null || (request.getIntentId() != null
                && !request.getIntentId().equals(intentId))) {
            return JsonResult.success(new HallActionDispatchResult(
                    intentId, request == null ? null : request.getActorAgentId(),
                    HallActionDispatcher.STATUS_FAILED,
                    "request is not authorized or valid"));
        }
        request.setIntentId(intentId);
        return JsonResult.success(hallActionDispatcher.dispatch(
                request, trustedCaller(authentication, callerAgentId)));
    }

    @GetMapping("/agents/{agentId}/mailbox")
    public JsonResult<Object> mailbox(
            @PathVariable String agentId,
            @RequestParam(required = false) String callerAgentId,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean includeTerminal,
            Authentication authentication) {
        return JsonResult.success(hallActionDispatcher.mailbox(
                agentId, taskId, cursor, limit, includeTerminal,
                trustedCaller(authentication, callerAgentId)));
    }

    private HallTrustedCaller trustedCaller(
            Authentication authentication, String callerAgentId) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            return null;
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object tenant = claims.get("jiacn");
        Object client = claims.get("client_id");
        if (!(tenant instanceof String tenantId) || !(client instanceof String clientId)
                || !validExact(tenantId, 50) || !validExact(clientId, 50)) {
            return null;
        }
        return new HallTrustedCaller(
                tenantId, clientId, validExact(callerAgentId, 100) ? callerAgentId : null);
    }

    private boolean validExact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
