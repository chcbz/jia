package cn.jia.chat.api;

import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.chat.service.AgentProfileCapabilitiesService;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Current owner's private profile, never a task grant or a marketplace mutation. */
@RestController
@RequestMapping("/agent")
public final class AgentProfileCapabilitiesController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentProfileCapabilitiesController.class);
    private final AgentProfileCapabilitiesService profiles;
    private final TenantScopeResolver tenants;
    public AgentProfileCapabilitiesController(AgentProfileCapabilitiesService profiles, TenantScopeResolver tenants) {
        this.profiles = profiles; this.tenants = tenants;
    }
    @GetMapping("/{agentId}/profile-capabilities")
    public ResponseEntity<JsonResult<AgentProfileCapabilitiesService.ProfileCapabilities>> read(
            @PathVariable String agentId, Authentication authentication, HttpServletRequest request) {
        var actor = HostingRentHttp.actor(authentication);
        if (agentId == null || agentId.isBlank() || !agentId.equals(agentId.strip())
                || agentId.codePointCount(0, agentId.length()) > 100
                || agentId.codePoints().anyMatch(Character::isISOControl)
                || request.getQueryString() != null || !request.getParameterMap().isEmpty()
                || request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null)
            throw new HostingRentApplicationException(400, "BAD_REQUEST");
        var context = EsContextHolder.getContext();
        if (context == null || !Objects.equals(tenants.resolve(authentication), actor.tenantId())
                || !Objects.equals(actor.tenantId(), context.getTenantId())
                || !Objects.equals(actor.clientId(), context.getClientId())
                || !Objects.equals(actor.ownerJiacn(), context.getJiacn()))
            throw new HostingRentApplicationException(404, "AGENT_PROFILE_NOT_FOUND");
        return headers(ResponseEntity.ok()).body(JsonResult.success(profiles.read(actor, agentId)));
    }
    @ExceptionHandler(HostingRentApplicationException.class)
    public ResponseEntity<JsonResult<Void>> identity(HostingRentApplicationException error) {
        return failure(switch (error.status()) { case 400 -> 400; case 401 -> 401; case 403, 404 -> 404; default -> 503; });
    }
    @ExceptionHandler(AgentServiceImpl.AgentBizException.class)
    public ResponseEntity<JsonResult<Void>> agent(AgentServiceImpl.AgentBizException error) {
        return failure(AgentErrorConstants.AGENT_FORBIDDEN.equals(error.getCode())
                || AgentErrorConstants.AGENT_NOT_FOUND.equals(error.getCode()) ? 404 : 503);
    }
    @ExceptionHandler(cn.jia.chat.service.ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> tenant(cn.jia.chat.service.ChatDeliberationException error) {
        return failure(error.reason() == cn.jia.chat.service.ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN ? 404 : 503);
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(RuntimeException error) {
        LOG.warn("AGENT_PROFILE_UNAVAILABLE requestId={} category={}",
                org.slf4j.MDC.get("request_id"), error.getClass().getSimpleName());
        return failure(503);
    }
    private static ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder builder) {
        return builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff");
    }
    private static ResponseEntity<JsonResult<Void>> failure(int status) {
        String code = switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 404 -> "AGENT_PROFILE_NOT_FOUND";
            default -> "AGENT_PROFILE_UNAVAILABLE";
        };
        String message = switch (status) {
            case 400 -> "Invalid profile request";
            case 401 -> "Authentication required";
            case 404 -> "Agent profile is unavailable";
            default -> "Agent profile could not be read";
        };
        return headers(ResponseEntity.status(status)).body(JsonResult.failure(code, message));
    }
}
