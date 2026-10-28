package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskGrantInputDTO;
import cn.jia.agent.entity.ControlledImagePointAndStartDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImagePointAndStartService;
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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/agent/tasks/{taskId}/point-and-start-controlled-image")
@ConditionalOnProperty(prefix="agent.controlled-image-provider",name="bridge-enabled",havingValue="true")
public final class ControlledImagePointAndStartController {
    private static final String CACHE="private, no-store";
    private static final long MAX_SAFE=9_007_199_254_740_991L;
    private static final Set<String> ROOT_FIELDS=Set.of("schemaVersion","assignment","providerConsent");
    private static final Set<String> CONSENT_FIELDS=Set.of("consentId","expectedVersion");
    private static final Set<String> ASSIGNMENT_FIELDS=Set.of("workflowVersion","businessAction",
            "expectedTaskVersion","requirementRevision","agentId","requestedOperations",
            "initialOperation","inputRefs");
    private static final Set<String> INPUT_FIELDS=Set.of("fileId","version","purpose");
    private static final ObjectMapper STRICT_JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ControlledImagePointAndStartService bridge;

    public ControlledImagePointAndStartController(ControlledImagePointAndStartService bridge) {
        this.bridge=Objects.requireNonNull(bridge);
    }

    @GetMapping(value="/request",produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<ControlledImagePointAndStartDTO.Receipt>> get(
            @PathVariable String taskId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            HttpServletRequest request,Authentication authentication) {
        exactHeaders(request,key);noQuery(request);
        return response(HttpStatus.OK,JsonResult.success(
                bridge.get(scope(authentication),taskId,key)));
    }

    @ExceptionHandler(ControlledImagePointAndStartService.Failure.class)
    public ResponseEntity<JsonResult<Void>> failure(ControlledImagePointAndStartService.Failure failure) {
        return switch(failure.reason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST,"CONTROLLED_IMAGE_BRIDGE_BAD_REQUEST");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND,"CONTROLLED_IMAGE_BRIDGE_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT,"CONTROLLED_IMAGE_BRIDGE_CONFLICT");
            case SOURCE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "CONTROLLED_IMAGE_BRIDGE_SOURCE_UNAVAILABLE");
        };
    }
    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<JsonResult<Void>> unauth(AuthenticationFailure failure) {
        return error(failure.forbidden?HttpStatus.FORBIDDEN:HttpStatus.UNAUTHORIZED,
                failure.forbidden?"CONTROLLED_IMAGE_BRIDGE_FORBIDDEN":
                        "CONTROLLED_IMAGE_BRIDGE_UNAUTHENTICATED");
    }
    @ExceptionHandler({BadRequest.class,IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<JsonResult<Void>> bad(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST,"CONTROLLED_IMAGE_BRIDGE_BAD_REQUEST");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> unknown(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_IMAGE_BRIDGE_SOURCE_UNAVAILABLE");
    }

    private static AgentTaskExecutionGrantService.Scope scope(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !authentication.isAuthenticated()) throw new AuthenticationFailure(false);
        Map<String,Object> claims=jwt.getToken().getClaims();
        Object owner=claims.get("jiacn"),client=claims.get("client_id");
        final String tenant;
        try { tenant=TenantClaimPolicy.resolve(claims,"0"); }
        catch (IllegalArgumentException invalid) { throw new AuthenticationFailure(true); }
        EsContext context=EsContextHolder.getContext();
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !valid(ownerJiacn,50) || "0".equals(ownerJiacn) || !valid(clientId,50)
                || !"0".equals(tenant) || context==null || !"0".equals(context.getTenantId())
                || !Objects.equals(ownerJiacn,context.getJiacn())
                || !Objects.equals(clientId,context.getClientId()))
            throw new AuthenticationFailure(true);
        return new AgentTaskExecutionGrantService.Scope("0",clientId,ownerJiacn);
    }
    private static void exactHeaders(HttpServletRequest request,String key) {
        List<String> values=Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size()!=1 || !Objects.equals(key,values.getFirst()) || key==null
                || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,99}")) throw new BadRequest();
    }
    private static void noQuery(HttpServletRequest request) {
        if (request.getParameterMap()!=null && !request.getParameterMap().isEmpty()) throw new BadRequest();
    }
    private static boolean integral(JsonNode value,int expected) {
        return value!=null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue()==expected;
    }
    private static boolean safeIntegral(JsonNode value,long min) {
        return value!=null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue()>=min && value.longValue()<=MAX_SAFE;
    }
    private static boolean safeInt(JsonNode value,int min) {
        return value!=null && value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue()>=min;
    }
    private static boolean text(JsonNode value) { return value!=null && value.isTextual(); }
    private static Set<String> fields(JsonNode node) {
        Set<String> result=new HashSet<>();node.propertyNames().forEach(result::add);return result;
    }
    private static boolean valid(String value,int max) {
        return value!=null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0,value.length())<=max
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static <T> ResponseEntity<T> response(HttpStatus status,T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE)
                .header("X-Content-Type-Options","nosniff")
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
    private static ResponseEntity<JsonResult<Void>> error(HttpStatus status,String code) {
        JsonResult<Void> body=JsonResult.failure(code,"Controlled image bridge request unavailable");

        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE)
                .header("X-Content-Type-Options","nosniff")
                .contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8)).body(body);
    }
    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden=forbidden; }
    }
    private static final class BadRequest extends RuntimeException { }
}
