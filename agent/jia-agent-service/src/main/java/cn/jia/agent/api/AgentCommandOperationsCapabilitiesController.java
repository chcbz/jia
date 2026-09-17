package cn.jia.agent.api;

import cn.jia.core.entity.JsonResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Objects;

/** Always-present, infrastructure-free discovery boundary for the privileged D09 read API. */
@RestController
@RequestMapping("/agent/internal/command-operations")
public class AgentCommandOperationsCapabilitiesController {
    static final String CONTRACT_VERSION = "command-observability-v1";
    static final String CACHE_CONTROL = "private, no-store";

    private final boolean readEnabled;

    public AgentCommandOperationsCapabilitiesController(
            @Value("${agent.rabbit-operations.read-enabled:false}") boolean readEnabled) {
        this.readEnabled = readEnabled;
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<CapabilityBody>> capabilities(Authentication authentication) {
        requireExactJwtScope(authentication);
        boolean hasReadAuthority = authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(AgentCommandOperationsController.AUTHORITY_READ::equals);
        CapabilityBody body;
        if (!hasReadAuthority) {
            body = new CapabilityBody(CONTRACT_VERSION, false, true, "FORBIDDEN");
        } else if (!readEnabled) {
            body = new CapabilityBody(CONTRACT_VERSION, false, true, "DISABLED");
        } else {
            body = new CapabilityBody(CONTRACT_VERSION, true, true, null);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(JsonResult.success(body));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return ResponseEntity.status(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorBody(
                        failure.forbidden ? "COMMAND_OPS_FORBIDDEN" : "COMMAND_OPS_UNAUTHENTICATED",
                        "Command operations access is unavailable"));
    }

    private void requireExactJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        requiredClaim(claims, "jiacn", 50);
        requiredClaim(claims, "client_id", 50);
        String subject = requiredClaim(claims, "sub", 100);
        if (!Objects.equals(authentication.getName(), subject)) {
            throw new AuthenticationFailure(true);
        }
    }

    private String requiredClaim(Map<String, Object> claims, String name, int maxLength) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || !exact(text, maxLength)) {
            throw new AuthenticationFailure(true);
        }
        return text;
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty()
                && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    public record CapabilityBody(
            String contractVersion,
            boolean available,
            boolean readOnly,
            String reason) {
    }

    public record ErrorBody(String code, String message) {
    }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;

        private AuthenticationFailure(boolean forbidden) {
            this.forbidden = forbidden;
        }
    }
}
