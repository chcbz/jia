package cn.jia.agent.api;

import cn.jia.agent.service.*;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.inject.Inject;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/agent")
public final class AgentExternalServicePolicyController {
    private final AgentExternalServicePolicyService service;
    private final ObjectMapper json;
    @Inject public AgentExternalServicePolicyController(AgentExternalServicePolicyService service,ObjectMapper json) {
        this.service=service;this.json=json;
    }
    @GetMapping("/{agentId}/external-service-policy")
    public ResponseEntity<?> read(@PathVariable String agentId,Authentication authentication) {
        return ok(service.read(scope(authentication),agentId));
    }
    @PutMapping("/{agentId}/external-service-policy")
    public ResponseEntity<?> write(@PathVariable String agentId,
            @RequestHeader(name="Idempotency-Key") String key,
            @cn.jia.core.annotation.ApiJsonData @RequestBody String raw,Authentication authentication) {
        var owner=scope(authentication);
        final boolean enabled;
        final long expected;
        try {
            var data=json.readTree(raw);
            if(!data.isObject() || data.size()!=2 || !data.has("enabled") || !data.has("expectedRevision")
                    || !data.get("enabled").isBoolean() || !data.get("expectedRevision").isTextual()
                    || !data.get("expectedRevision").asText().matches("0|[1-9][0-9]*")) throw bad();
            expected=Long.parseLong(data.get("expectedRevision").asText());
            enabled=data.get("enabled").asBoolean();
        } catch(AgentTaskExecutionGrantException failure) { throw failure; }
        catch(Exception invalid) { throw bad(); }
        return ok(service.write(owner,agentId,key,enabled,expected));
    }

    @GetMapping("/{agentId}/external-service-policy/request")
    public ResponseEntity<?> receipt(@PathVariable String agentId,@RequestHeader(name="Idempotency-Key") String key,Authentication authentication) {
        return ok(service.receipt(scope(authentication),agentId,key));
    }
    @GetMapping("/tasks/{taskId}/assignee-candidates")
    public ResponseEntity<?> candidates(@PathVariable String taskId,
            @RequestParam(required=false) String afterAgentId,@RequestParam(defaultValue="50") int pageSize,Authentication authentication) {
        return ok(service.candidates(scope(authentication),taskId,afterAgentId,pageSize));
    }
    @ExceptionHandler(AgentTaskExecutionGrantException.class)
    public ResponseEntity<?> failure(AgentTaskExecutionGrantException failure) {
        return ResponseEntity.status(failure.status()).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .body(JsonResult.failure(failure.code(),failure.getMessage()));
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    public ResponseEntity<?> invalidHttpArgument(Exception invalid) {
        return failure(bad());
    }
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<?> duplicate(org.springframework.dao.DuplicateKeyException failure) {
        return failure(new AgentTaskExecutionGrantException(AgentTaskExecutionGrantException.Reason.IDEMPOTENCY_CONFLICT,"Policy operation conflicts with a committed request"));
    }
    private static ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(JsonResult.success(value));
    }
    private static AgentTaskExecutionGrantService.Scope scope(Authentication authentication) {
        if(!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) throw unauthorized();
        var claims=jwt.getToken().getClaims();
        Object owner=claims.get("jiacn"),client=claims.get("client_id");
        try {
            if(!"0".equals(TenantClaimPolicy.resolve(claims,"0")) || !(owner instanceof String o)
                    || !(client instanceof String c) || !exact(o) || "0".equals(o) || !exact(c)) throw unauthorized();
            return new AgentTaskExecutionGrantService.Scope("0",c,o);
        } catch(IllegalArgumentException invalid) { throw unauthorized(); }
    }
    private static boolean exact(String s) { return !s.isBlank()&&s.equals(s.strip())&&s.length()<=50&&!s.chars().anyMatch(Character::isISOControl); }
    private static AgentTaskExecutionGrantException bad() {return new AgentTaskExecutionGrantException(AgentTaskExecutionGrantException.Reason.BAD_REQUEST,"Invalid policy command");}
    private static AgentTaskExecutionGrantException unauthorized() {return new AgentTaskExecutionGrantException(AgentTaskExecutionGrantException.Reason.UNAUTHENTICATED,"Authentication required");}
}
