package cn.jia.oauth.api;

import cn.jia.core.entity.JsonResult;
import cn.jia.oauth.security.AccountSecurityJwtClaims;
import cn.jia.user.security.AccountSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(AccountSecurityService.class)
@RequiredArgsConstructor
public class SessionRevocationController {
    private static final String SESSION_EPOCH_CONFLICT = "SESSION_EPOCH_CONFLICT";
    private static final String AUTH_EPOCH_EXHAUSTED = "AUTH_EPOCH_EXHAUSTED";

    private final AccountSecurityService accountSecurityService;

    @PostMapping("/user/me/sessions/revoke-all")
    public ResponseEntity<?> revokeAll(Authentication authentication, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty() || requestHasBody(request)) {
            return ResponseEntity.badRequest().build();
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        AccountSecurityJwtClaims identity;
        try {
            identity = AccountSecurityJwtClaims.parse(jwtAuthentication.getToken());
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (identity.tokenClass() != AccountSecurityJwtClaims.TokenClass.USER
                && identity.tokenClass() != AccountSecurityJwtClaims.TokenClass.LEGACY_USER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        long userId = identity.userId();
        long expectedEpoch = identity.authEpoch();
        if (identity.tokenClass() == AccountSecurityJwtClaims.TokenClass.LEGACY_USER) {
            try {
                userId = accountSecurityService.findUniqueByExactJiacn(identity.jiacn())
                        .filter(snapshot -> snapshot.isAuthenticatable() && snapshot.authEpoch() == 0)
                        .map(snapshot -> snapshot.userId())
                        .orElse(0L);
            } catch (RuntimeException exception) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            expectedEpoch = 0;
        }
        if (userId <= 0) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (expectedEpoch == Long.MAX_VALUE) {
            return conflict(AUTH_EPOCH_EXHAUSTED, "Authentication epoch is exhausted");
        }

        int updated = accountSecurityService.revokeAllSessions(userId, expectedEpoch);
        if (updated == 1) {
            return ResponseEntity.noContent().build();
        }
        return conflict(SESSION_EPOCH_CONFLICT, "Session epoch conflict");
    }

    private static boolean requestHasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    private static ResponseEntity<JsonResult<Void>> conflict(String code, String message) {
        JsonResult<Void> result = new JsonResult<>();
        result.setCode(code);
        result.setMsg(message);
        result.setStatus(HttpStatus.CONFLICT.value());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
}
