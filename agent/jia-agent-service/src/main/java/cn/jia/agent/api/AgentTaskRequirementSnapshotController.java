package cn.jia.agent.api;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementReadException;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.core.entity.JsonResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Owner-only read edge for the current immutable task requirement. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
@ConditionalOnProperty(prefix = "agent.task-requirement-snapshot", name = "read-enabled",
        havingValue = "true")
public final class AgentTaskRequirementSnapshotController {
    static final String CACHE_CONTROL = "private, no-store";
    private final AgentTaskRequirementSnapshotService snapshots;

    public AgentTaskRequirementSnapshotController(AgentTaskRequirementSnapshotService snapshots) {
        this.snapshots=Objects.requireNonNull(snapshots,"snapshots");
    }

    @GetMapping(value = "/{taskId}/requirements/current", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<CurrentRequirementView>> current(
            @PathVariable String taskId, HttpServletRequest request, Authentication authentication) {
        AgentTaskExecutionGrantService.Scope scope=requireOwnerJwt(authentication);
        requireExact(taskId,100);
        if (!request.getParameterMap().isEmpty()) throw new InvalidRequest();
        AgentTaskRequirementSnapshotService.CurrentSnapshot current=snapshots.readCurrent(scope,taskId);
        if (current==null || !taskId.equals(current.taskId()) || current.taskVersion()<0
                || current.requirementRevision()<1 || current.title()==null
                || current.title().isBlank() || current.sha256()==null
                || !current.sha256().matches("[0-9a-f]{64}")
                || !("CREATE".equals(current.source()) || "RECONFIRM".equals(current.source()))) {
            throw new AgentTaskRequirementReadException(
                    AgentTaskRequirementReadException.Reason.INTEGRITY_ERROR);
        }
        CurrentRequirementView body=new CurrentRequirementView(current.taskId(),
                Long.toString(current.taskVersion()),Long.toString(current.requirementRevision()),
                current.title(),current.description(),current.sha256(),current.source());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(JsonResult.success(body));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<JsonResult<Void>> authentication(AuthenticationFailure ignored) {
        return error(HttpStatus.UNAUTHORIZED,"UNAUTHENTICATED","Authentication is required");
    }

    @ExceptionHandler({InvalidRequest.class, IllegalArgumentException.class})
    public ResponseEntity<JsonResult<Void>> badRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST,"BAD_REQUEST","Invalid current requirement request");
    }

    @ExceptionHandler(AgentTaskRequirementReadException.class)
    public ResponseEntity<JsonResult<Void>> requirementFailure(
            AgentTaskRequirementReadException failure) {
        return switch (failure.reason()) {
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND,"REQUIREMENT_NOT_FOUND",
                    "Current requirement is unavailable in the requested scope");
            case RECONFIRM_REQUIRED -> error(HttpStatus.CONFLICT,"REQUIREMENT_RECONFIRM_REQUIRED",
                    "The task requirement must be confirmed by its owner");
            case INTEGRITY_ERROR -> {
                log.error("Current requirement read failed: reason=integrity_error");
                yield error(HttpStatus.INTERNAL_SERVER_ERROR,"REQUIREMENT_INTEGRITY_ERROR",
                        "Current requirement integrity validation failed");
            }
            case SOURCE_UNAVAILABLE -> {
                log.error("Current requirement read failed: reason=source_unavailable type={}",
                        causeType(failure));
                yield unavailable();
            }
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> unexpected(Exception failure) {
        log.error("Current requirement read failed: reason=unexpected type={}",
                failure.getClass().getName());
        return unavailable();
    }

    private static AgentTaskExecutionGrantService.Scope requireOwnerJwt(Authentication authentication) {
        if (authentication==null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure();
        }
        Map<String,Object> claims=jwt.getToken().getClaims();
        Object owner=claims.get("jiacn");
        Object client=claims.get("client_id");
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !validExact(ownerJiacn,50) || "0".equals(ownerJiacn)
                || !validExact(clientId,50)) {
            throw new AuthenticationFailure();
        }
        return new AgentTaskExecutionGrantService.Scope("0",clientId,ownerJiacn);
    }

    private static void requireExact(String value,int maxCodePoints) {
        if (!validExact(value,maxCodePoints)) throw new InvalidRequest();
    }

    private static boolean validExact(String value,int maxCodePoints) {
        return value!=null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0,value.length())<=maxCodePoints
                && !value.codePoints().allMatch(AgentTaskRequirementSnapshotController::isPadding)
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index=0;index<value.length();index++) {
            char unit=value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index>=value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static ResponseEntity<JsonResult<Void>> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"REQUIREMENT_SOURCE_UNAVAILABLE",
                "Current requirement source is temporarily unavailable");
    }

    private static ResponseEntity<JsonResult<Void>> error(
            HttpStatus status,String code,String message) {
        JsonResult<Void> body=JsonResult.failure(code,message);
        body.setStatus(status.value());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON,StandardCharsets.UTF_8))
                .body(body);
    }

    private static String causeType(Throwable failure) {
        Throwable cause=failure.getCause();
        return cause==null?failure.getClass().getName():cause.getClass().getName();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CurrentRequirementView(String taskId,String taskVersion,
            String requirementRevision,String title,String description,
            String contentSha256,String source) { }

    private static final class AuthenticationFailure extends RuntimeException { }
    private static final class InvalidRequest extends RuntimeException { }
}
