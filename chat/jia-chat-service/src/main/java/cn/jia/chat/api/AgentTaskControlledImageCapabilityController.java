package cn.jia.chat.api;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import cn.jia.agent.service.ControlledImageExecutionSessionLookup;
import cn.jia.agent.service.ControlledImageProviderAuthorityLookup;
import cn.jia.chat.service.ControlledImagePointAndStartCapabilityService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Owner JWT-only controlled lane observation; this endpoint never creates consent or execution. */
@RestController
@RequestMapping("/agent/tasks")
@ConditionalOnProperty(prefix="agent.controlled-image-provider",name="bridge-enabled",havingValue="true")
public final class AgentTaskControlledImageCapabilityController {
    private static final String CACHE="private, no-store";
    private final ControlledImagePointAndStartCapabilityService capabilities;
    public AgentTaskControlledImageCapabilityController(ControlledImagePointAndStartCapabilityService capabilities) {
        this.capabilities=Objects.requireNonNull(capabilities);
    }
    @GetMapping(value="/{taskId}/point-and-start-controlled-image-capability",
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<ControlledImagePointAndStartCapabilityService.Capability>> read(
            @PathVariable String taskId,@RequestParam(name="targetAgentId",required=false)String targetAgentId,
            HttpServletRequest request,Authentication authentication) {
        var scope=scope(authentication);id(taskId,100);id(targetAgentId,100);
        Map<String,String[]> query=request.getParameterMap();String[] targets=query.get("targetAgentId");
        if(query.size()!=1||targets==null||targets.length!=1||!Objects.equals(targetAgentId,targets[0]))
            throw new BadRequest();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE)
                .header("X-Content-Type-Options","nosniff")
                .body(JsonResult.success(capabilities.read(scope,taskId,targetAgentId)));
    }
    @ExceptionHandler(AuthenticationFailure.class)
    ResponseEntity<JsonResult<Void>> unauth(AuthenticationFailure failure) {
        return error(failure.forbidden?HttpStatus.FORBIDDEN:HttpStatus.UNAUTHORIZED,
                failure.forbidden?"CONTROLLED_IMAGE_CAPABILITY_FORBIDDEN":
                        "CONTROLLED_IMAGE_CAPABILITY_UNAUTHENTICATED");
    }
    @ExceptionHandler({BadRequest.class,IllegalArgumentException.class}) ResponseEntity<JsonResult<Void>> bad() {
        return error(HttpStatus.BAD_REQUEST,"CONTROLLED_IMAGE_CAPABILITY_BAD_REQUEST");
    }
    @ExceptionHandler(AgentTaskPointAndStartPolicyService.Failure.class)
    ResponseEntity<JsonResult<Void>> policy(AgentTaskPointAndStartPolicyService.Failure failure) {
        return failure.reason()==AgentTaskPointAndStartPolicyService.Failure.Reason.NOT_FOUND
                ?error(HttpStatus.NOT_FOUND,"CONTROLLED_IMAGE_CAPABILITY_NOT_FOUND")
                :error(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_IMAGE_CAPABILITY_SOURCE_UNAVAILABLE");
    }
    @ExceptionHandler({ControlledImageExecutionSessionLookup.SourceUnavailable.class,
            ControlledImageProviderAuthorityLookup.SourceUnavailable.class,
            ControlledImagePointAndStartCapabilityService.SourceUnavailable.class})
    ResponseEntity<JsonResult<Void>> unavailable(RuntimeException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_IMAGE_CAPABILITY_SOURCE_UNAVAILABLE");
    }
    @ExceptionHandler(Exception.class) ResponseEntity<JsonResult<Void>> unexpected(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_IMAGE_CAPABILITY_SOURCE_UNAVAILABLE");
    }
    private static AgentTaskExecutionGrantService.Scope scope(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated())
            throw new AuthenticationFailure(false);
        Map<String,Object> claims=jwt.getToken().getClaims();
        Object owner=claims.get("jiacn"),client=claims.get("client_id");
        final String tenant;
        try { tenant=TenantClaimPolicy.resolve(claims,"0"); }
        catch (IllegalArgumentException invalid) { throw new AuthenticationFailure(true); }
        EsContext context=EsContextHolder.getContext();
        if (!(owner instanceof String o) || !(client instanceof String c) || "0".equals(o)
                || !valid(o,50) || !valid(c,50) || !"0".equals(tenant)
                || context==null || !"0".equals(context.getTenantId())
                || !Objects.equals(o,context.getJiacn())
                || !Objects.equals(c,context.getClientId())) throw new AuthenticationFailure(true);
        return new AgentTaskExecutionGrantService.Scope("0",c,o);
    }
    private static void id(String value,int max){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,"+(max-1)+"}"))throw new BadRequest();}
    private static boolean valid(String value,int max){return value!=null&&!value.isBlank()&&value.equals(value.strip())&&value.length()<=max&&value.chars().noneMatch(Character::isISOControl);}
    private static ResponseEntity<JsonResult<Void>> error(HttpStatus status,String code){var body=JsonResult.<Void>failure(code,"Controlled image capability unavailable");body.setStatus(status.value());return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE).header("X-Content-Type-Options","nosniff").contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8)).body(body);}
    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden=forbidden; }
    }
    private static final class BadRequest extends RuntimeException { }
}
