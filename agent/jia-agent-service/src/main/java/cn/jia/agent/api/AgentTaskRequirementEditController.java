package cn.jia.agent.api;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementEditService;
import cn.jia.agent.service.AgentTaskRequirementReadException;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;

@RestController
@RequestMapping("/agent/tasks/{taskId}/requirements/edit")
public class AgentTaskRequirementEditController {
    private final AgentTaskRequirementEditService service;
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public AgentTaskRequirementEditController(AgentTaskRequirementEditService service) { this.service=service; }
    record EditRequest(String expectedTaskVersion,String expectedRequirementRevision,String title,
                       String description,List<AgentTaskRequirementEditService.Attachment> attachments) { }

    @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<AgentTaskRequirementEditService.View>> read(@PathVariable String taskId,
            @RequestHeader(value="Idempotency-Key",required=false) String key, Authentication authentication) {
        var owner=scope(authentication);
        return ok(key==null?service.read(owner,taskId):service.readSaved(owner,taskId,key));
    }
    @PutMapping(consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<AgentTaskRequirementEditService.View>> save(@PathVariable String taskId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            HttpServletRequest request,Authentication authentication) {
        var owner=scope(authentication);
        final EditRequest body;
        try {
            var data=JSON.readTree(cn.jia.core.config.ApiJsonInput.readData(request));
            if (data==null || !data.isObject() || data.get("expectedTaskVersion")==null || !data.get("expectedTaskVersion").isString()
                    || data.get("expectedRequirementRevision")==null || !data.get("expectedRequirementRevision").isString()) throw new IllegalArgumentException();
            if (data.get("title")==null || !data.get("title").isString() || data.get("description")==null
                    || !(data.get("description").isString() || data.get("description").isNull())) throw new IllegalArgumentException();
            if (data.get("attachments")!=null && data.get("attachments").isArray()) for (var a:data.get("attachments"))
                if (a.get("fileId")==null || !a.get("fileId").isString() || a.get("version")==null || !a.get("version").isIntegralNumber() || !a.get("version").canConvertToInt()) throw new IllegalArgumentException();
            body=JSON.treeToValue(data,EditRequest.class);
        }
        catch (org.springframework.http.converter.HttpMessageNotReadableException protocol) { throw protocol; }
        catch (Exception invalid) { throw new AgentTaskRequirementEditService.Failure(AgentTaskRequirementEditService.Reason.BAD_REQUEST); }
        if (body==null) throw new AgentTaskRequirementEditService.Failure(AgentTaskRequirementEditService.Reason.BAD_REQUEST);
        return ok(service.save(owner,taskId,key,new AgentTaskRequirementEditService.Command(
                version(body.expectedTaskVersion(),true),version(body.expectedRequirementRevision(),false),
                body.title(),body.description(),body.attachments())));
    }
    private static long version(String value,boolean zero) {
        if (value==null || !value.matches(zero?"0|[1-9][0-9]*":"[1-9][0-9]*"))
            throw new AgentTaskRequirementEditService.Failure(AgentTaskRequirementEditService.Reason.BAD_REQUEST);
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new AgentTaskRequirementEditService.Failure(AgentTaskRequirementEditService.Reason.BAD_REQUEST); }
    }
    private static AgentTaskExecutionGrantService.Scope scope(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated()) throw new AuthFailure(false);
        var claims=jwt.getToken().getClaims();
        if (!(claims.get("jiacn") instanceof String owner) || owner.isBlank() || "0".equals(owner)
                || !(claims.get("client_id") instanceof String client) || client.isBlank()) throw new AuthFailure(true);
        final String tenant;
        try { tenant=TenantClaimPolicy.resolve(claims); }
        catch (IllegalArgumentException invalid) { throw new AuthFailure(true); }
        return new AgentTaskExecutionGrantService.Scope(tenant,client,owner);
    }
    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<JsonResult<Void>> auth(AuthFailure failure) {
        return error(failure.forbidden?403:401,failure.forbidden?"REQUIREMENT_FORBIDDEN":"UNAUTHENTICATED");
    }
    @ExceptionHandler(AgentTaskRequirementEditService.Failure.class)
    public ResponseEntity<JsonResult<Void>> failure(AgentTaskRequirementEditService.Failure failure) {
        return switch(failure.reason()) {
            case BAD_REQUEST -> error(400,"BAD_REQUEST");
            case NOT_FOUND -> error(404,"REQUIREMENT_NOT_FOUND");
            case CONFLICT -> error(409,"REQUIREMENT_CHANGED");
            case NOT_EDITABLE -> error(409,"REQUIREMENT_NOT_EDITABLE");
            case UNAVAILABLE -> error(503,"REQUIREMENT_UNAVAILABLE");
        };
    }
    @ExceptionHandler(AgentTaskCollaborationException.class)
    public ResponseEntity<JsonResult<Void>> taskFailure(AgentTaskCollaborationException failure) {
        return error(failure.getReason()==AgentTaskCollaborationException.Reason.NOT_FOUND ||
                failure.getReason()==AgentTaskCollaborationException.Reason.FORBIDDEN?404:503,"REQUIREMENT_UNAVAILABLE");
    }
    @ExceptionHandler(AgentTaskRequirementReadException.class)
    public ResponseEntity<JsonResult<Void>> readFailure(AgentTaskRequirementReadException failure) {
        return error(failure.reason()==AgentTaskRequirementReadException.Reason.NOT_FOUND?404:
                failure.reason()==AgentTaskRequirementReadException.Reason.RECONFIRM_REQUIRED?409:503,"REQUIREMENT_UNAVAILABLE");
    }
    @ExceptionHandler(PersonalWorkspaceTaskLinkService.Failure.class)
    public ResponseEntity<JsonResult<Void>> linkFailure(PersonalWorkspaceTaskLinkService.Failure failure) {
        return error(switch(failure.getReason()) {
            case NOT_FOUND -> 404;
            case BAD_REQUEST -> 400;
            case LINK_CHANGED, IDEMPOTENCY_CONFLICT, OUTPUT_MANAGED_BY_EXECUTION -> 409;
            default -> 503;
        },"REQUIREMENT_MATERIALS_UNAVAILABLE");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> unexpected(Exception failure) { return error(503,"REQUIREMENT_UNAVAILABLE"); }
    private static <T> ResponseEntity<JsonResult<T>> ok(T view) {
        return cn.jia.core.config.ApiJsonResponses.success(ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(view));
    }
    private static ResponseEntity<JsonResult<Void>> error(int status,String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .body(JsonResult.failure(code,"Matter requirement request was not accepted"));
    }
    static final class AuthFailure extends RuntimeException {
        final boolean forbidden; AuthFailure(boolean forbidden) { this.forbidden=forbidden; }
    }
}
