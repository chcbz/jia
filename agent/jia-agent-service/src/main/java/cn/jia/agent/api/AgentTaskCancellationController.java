package cn.jia.agent.api;

import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.service.AgentTaskCancellationService;
import cn.jia.core.security.TenantClaimPolicy;
import jakarta.servlet.http.HttpServletRequest;
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
import tools.jackson.databind.json.JsonMapper;
import java.util.Objects;

/** Owner cancellation, not an Agent/member capability. JWT is the sole actor/scope authority. */
@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskCancellationController {
    private static final JsonMapper JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AgentTaskCancellationService service;
    public AgentTaskCancellationController(AgentTaskCancellationService service) {
        this.service=Objects.requireNonNull(service);
    }

    @PostMapping(value="/{taskId}/cancel", consumes=MediaType.APPLICATION_JSON_VALUE,
            produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskCancellationService.Receipt> cancel(@PathVariable String taskId,
            HttpServletRequest request, Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) {
            throw new AuthenticationFailure(false);
        }
        String owner=claim(jwt,"jiacn",50), client=claim(jwt,"client_id",50), actor=claim(jwt,"sub",100);
        final String tenant;
        try { tenant=TenantClaimPolicy.resolve(jwt.getToken().getClaims(),"0"); }
        catch (IllegalArgumentException invalid) { throw new AuthenticationFailure(true); }
        if (!"0".equals(tenant) || "0".equals(owner) || "0".equals(client) || !actor.equals(authentication.getName())) {
            throw new AuthenticationFailure(true);
        }
        if (!exact(taskId,100) || request.getQueryString()!=null && !request.getQueryString().isEmpty()) {
            throw badRequest();
        }
        long version;
        try {
            // Bounded request, no free-form reason/identity/lease/credential payload accepted.
            byte[] bytes=request.getInputStream().readNBytes(1025);
            if (bytes.length>1024) throw badRequest();
            JsonNode root=JSON.readTree(bytes);
            JsonNode expected=root==null ? null : root.get("expectedTaskVersion");
            if (root==null || !root.isObject() || root.size()!=1 || expected==null
                    || !expected.isIntegralNumber() || !expected.canConvertToLong()) throw badRequest();
            version=expected.longValue();
            if (version<0 || version==Long.MAX_VALUE) throw badRequest();
        } catch (Exception failure) { throw badRequest(); }
        var receipt=service.cancel("0",client,owner,actor,taskId,version);
        if (receipt==null || !taskId.equals(receipt.taskId()) || !"cancelled".equals(receipt.status())
                || receipt.taskVersion()<0) {
            throw new IllegalStateException("Invalid cancellation receipt");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(receipt);
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<Error> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "CANCEL_FORBIDDEN" : "CANCEL_UNAUTHENTICATED");
    }
    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<Error> badRequest(RequestFailure ignored) { return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST"); }
    @ExceptionHandler(AgentTaskStateException.class)
    public ResponseEntity<Error> state(AgentTaskStateException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST,"BAD_REQUEST");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT,"TASK_VERSION_CONFLICT");
            case INVALID_TRANSITION, RESERVED_FOR_CLAIM_PROTOCOL, LEASE_INVALID ->
                    error(HttpStatus.CONFLICT,"INITIAL_CANCEL_UNSUPPORTED");
            case INVALID_PERSISTED_STATE -> error(HttpStatus.SERVICE_UNAVAILABLE,"CANCEL_UNAVAILABLE");
        };
    }
    @ExceptionHandler(AgentTaskCollaborationException.class)
    public ResponseEntity<Error> collaboration(AgentTaskCollaborationException failure) {
        return error(failure.getReason()==AgentTaskCollaborationException.Reason.NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.SERVICE_UNAVAILABLE,
                failure.getReason()==AgentTaskCollaborationException.Reason.NOT_FOUND
                        ? "TASK_NOT_FOUND" : "CANCEL_UNAVAILABLE");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> unexpected(Exception ignored) { return error(HttpStatus.SERVICE_UNAVAILABLE,"CANCEL_UNAVAILABLE"); }
    private static String claim(JwtAuthenticationToken jwt,String key,int max) {
        Object value=jwt.getToken().getClaims().get(key);
        if (!(value instanceof String text) || !exact(text,max)) throw new AuthenticationFailure(true);
        return text;
    }
    private static boolean exact(String text,int max) {
        return text!=null && !text.isBlank() && text.length()<=max && text.equals(text.strip())
                && text.chars().noneMatch(Character::isISOControl);
    }
    private static RequestFailure badRequest() { return new RequestFailure(); }
    private static ResponseEntity<Error> error(HttpStatus status,String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .body(new Error(code,"Task cancellation request was not accepted"));
    }
    public record Error(String code,String message) { }
    private static final class RequestFailure extends RuntimeException { }
    private static final class AuthenticationFailure extends RuntimeException {
        final boolean forbidden;
        AuthenticationFailure(boolean forbidden) { this.forbidden=forbidden; }
    }
}
