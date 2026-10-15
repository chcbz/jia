package cn.jia.chat.api;

import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.TaskSkillPolicyService;
import cn.jia.chat.service.*;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.Objects;

/** Browser-safe directory only. A GET never upgrades grants, executes, or approves costs. */
@RestController
@RequestMapping("/agent/tasks")
public final class TaskSkillCapabilityController {
    private final ChatActionCapabilityService capabilities;
    private final HumanSenderIdentityResolver identities;
    private final TenantScopeResolver tenants;
    public TaskSkillCapabilityController(ChatActionCapabilityService capabilities,HumanSenderIdentityResolver identities,TenantScopeResolver tenants) {
        this.capabilities=capabilities;this.identities=identities;this.tenants=tenants;
    }
    @GetMapping("/{taskId}/skill-capabilities")
    public ResponseEntity<JsonResult<ChatActionCapabilityService.TaskCatalog>> read(@PathVariable String taskId,
            @RequestParam String targetAgentId,Authentication authentication,HttpServletRequest request) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) throw missing();
        if (request.getQueryString() == null || request.getParameterMap().size() != 1 || (request.getParameterValues("targetAgentId") == null || request.getParameterValues("targetAgentId").length != 1)
                || request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) throw missing();
        String tenant=tenants.resolve(authentication);var context=EsContextHolder.getContext();
        if (context==null || !Objects.equals(tenant,context.getTenantId()) || !Objects.equals(jwt.getToken().getClaimAsString("jiacn"),context.getJiacn())
                || !Objects.equals(jwt.getToken().getClaimAsString("client_id"),context.getClientId())) throw missing();
        var sender=identities.resolve(context);
        if(!"0".equals(tenant)||sender==null||!ServerResolvedSender.USER_TYPE.equals(sender.type())||!exact(taskId)||!exact(targetAgentId))throw missing();
        var result=capabilities.taskCatalog(new ChatActionCapabilityService.Scope(tenant,sender.jiacn(),sender.clientId(),targetAgentId),taskId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(JsonResult.success(result));
    }
    @ExceptionHandler(TaskSkillPolicyService.Failure.class)
    public ResponseEntity<JsonResult<Void>> policy(TaskSkillPolicyService.Failure error) {
        return failure(error.reason()==TaskSkillPolicyService.Failure.Reason.NOT_FOUND?HttpStatus.NOT_FOUND:HttpStatus.SERVICE_UNAVAILABLE);
    }
    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> identity(ChatDeliberationException error) {
        return failure(error.reason()==ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN?HttpStatus.NOT_FOUND:HttpStatus.SERVICE_UNAVAILABLE);
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> unavailable(RuntimeException error) {return failure(HttpStatus.SERVICE_UNAVAILABLE);}
    private static ResponseEntity<JsonResult<Void>> failure(HttpStatus status) {
        var result=JsonResult.<Void>failure("SKILL_CAPABILITIES_"+status.value(),status==HttpStatus.NOT_FOUND?"Task is unavailable":"Skill directory is unavailable");
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(result);
    }
    private static ChatDeliberationException missing(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Task is unavailable");}
    private static boolean exact(String s){return s!=null&&!s.isBlank()&&s.equals(s.strip())&&s.length()<=100&&s.codePoints().noneMatch(Character::isISOControl);}
}
