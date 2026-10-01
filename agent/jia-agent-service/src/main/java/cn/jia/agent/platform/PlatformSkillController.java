package cn.jia.agent.platform;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static cn.jia.agent.platform.PlatformSkillException.require;
import static cn.jia.agent.platform.PlatformSkillInstallationService.*;

@RestController
@ConditionalOnProperty(prefix="agent.platform-skills",name="enabled",havingValue="true")
public final class PlatformSkillController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Set<String> REQUEST_FIELDS=Set.of("agentId","bindingVersion","skillKey","skillVersion");
    private static final Set<String> RESULT_FIELDS=Set.of("schemaVersion","installationId","commandId","attempt","executionEpoch",
            "challengeId","packageSha256","outcome","errorCode");
    private final PlatformSkillInstallationService installs;
    public PlatformSkillController(PlatformSkillInstallationService installs) { this.installs=installs; }
    @GetMapping("/agent/platform-skills/catalog")
    public ResponseEntity<List<PlatformSkillCatalogView>> catalog(Authentication auth,HttpServletRequest request) {
        noQuery(request);
        return privateResponse(installs.catalog(actor(auth)));
    }
    @PostMapping("/agent/platform-skills/installations")
    public ResponseEntity<View> request(@RequestBody byte[] bytes,Authentication auth,HttpServletRequest request) {
        noQuery(request); var actor=actor(auth);
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        require(keys.size()==1,400,"PLATFORM_SKILL_IDEMPOTENCY_REQUIRED");
        return privateResponse(installs.request(actor,keys.getFirst(),decodeRequest(bytes)));
    }
    @GetMapping("/agent/platform-skills/installations/{installationId}")
    public ResponseEntity<View> status(@PathVariable String installationId,Authentication auth,HttpServletRequest request) {
        noQuery(request);return privateResponse(installs.status(actor(auth),installationId));
    }
    @GetMapping("/internal/agent/platform-skills/installations/{installationId}/package")
    public ResponseEntity<byte[]> download(@PathVariable String installationId,Authentication auth,HttpServletRequest request) {
        nativeRequest(request);
        byte[] bytes=installs.packageBytes(nativeScope(auth),installationId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.parseMediaType("application/zip")).contentLength(bytes.length).body(bytes);
    }
    @PostMapping("/internal/agent/platform-skills/installations/{installationId}/result")
    public ResponseEntity<View> result(@PathVariable String installationId,@RequestBody byte[] bytes,Authentication auth,HttpServletRequest request) {
        nativeRequest(request);return privateResponse(installs.result(nativeScope(auth),installationId,decodeResult(bytes)));
    }
    static Request decodeRequest(byte[] bytes) { return decode(bytes,REQUEST_FIELDS,Set.of(),Request.class); }
    static Result decodeResult(byte[] bytes) { return decode(bytes,RESULT_FIELDS,Set.of("schemaVersion","attempt"),Result.class); }
    private static <T> T decode(byte[] bytes,Set<String> fields,Set<String> integers,Class<T> type) {
        require(bytes!=null && bytes.length>0 && bytes.length<=4096,400,"PLATFORM_SKILL_REQUEST_INVALID");
        try {
            JsonNode node=JSON.readTree(bytes);require(node!=null && node.isObject(),400,"PLATFORM_SKILL_REQUEST_INVALID");
            var names=new HashSet<String>();node.propertyNames().forEach(names::add);
            require(names.equals(fields),400,"PLATFORM_SKILL_REQUEST_INVALID");
            for(var field:fields) {
                var value=node.get(field);
                require(integers.contains(field) ? value.isIntegralNumber() && value.canConvertToInt()
                        : value.isTextual() || "errorCode".equals(field) && value.isNull(),400,"PLATFORM_SKILL_REQUEST_INVALID");
            }
            return JSON.treeToValue(node,type);
        } catch(PlatformSkillException invalid) {throw invalid;}
        catch(Exception invalid) {throw new PlatformSkillException(400,"PLATFORM_SKILL_REQUEST_INVALID");}
    }
    private static Actor actor(Authentication auth) {
        require(auth instanceof JwtAuthenticationToken && auth.isAuthenticated(),401,"PLATFORM_SKILL_UNAUTHENTICATED");
        var jwt=(JwtAuthenticationToken)auth;var claims=jwt.getToken().getClaims();
        return new Actor(claim(claims,"sub"),new PlatformInstallationStore.Scope("0",claim(claims,"client_id"),claim(claims,"jiacn")));
    }
    private static String claim(Map<String,Object> claims,String field) {
        require(claims.get(field) instanceof String,403,"PLATFORM_SKILL_FORBIDDEN");return (String)claims.get(field);
    }
    private static AgentRuntimeAuthentication.Scope nativeScope(Authentication auth) {
        require(auth instanceof AgentRuntimeAuthentication && auth.isAuthenticated(),401,"PLATFORM_SKILL_NATIVE_UNAUTHENTICATED");
        return ((AgentRuntimeAuthentication)auth).getPrincipal();
    }
    private static void noQuery(HttpServletRequest r) { require(r.getQueryString()==null,400,"PLATFORM_SKILL_REQUEST_INVALID"); }
    private static void nativeRequest(HttpServletRequest r) { noQuery(r);require(r.getHeader("Origin")==null,403,"PLATFORM_SKILL_NATIVE_FORBIDDEN"); }
    private static <T> ResponseEntity<T> privateResponse(T body) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(body); }
    @ExceptionHandler(PlatformSkillException.class)
    public ResponseEntity<Map<String,String>> failure(PlatformSkillException failure) {
        return ResponseEntity.status(failure.status()).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(Map.of("code",failure.code()));
    }
}
