package cn.jia.oauth.api;

import cn.jia.core.entity.JsonResult;
import cn.jia.test.BaseMockTest;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRevocationControllerTest extends BaseMockTest {
    @Mock AccountSecurityService service;

    @Test
    void newUserTokenPerformsOneCasAndReturnsEmpty204() {
        when(service.revokeAllSessions(17, 4)).thenReturn(1);
        ResponseEntity<?> response = controller().revokeAll(userAuth(17, "Jia-A", 4), new MockHttpServletRequest());
        assertEquals(204, response.getStatusCode().value());
        assertNull(response.getBody());
        verify(service, times(1)).revokeAllSessions(17, 4);
        verifyNoMoreInteractions(service);
    }

    @Test
    void staleCasConflictsWithoutReadOrRetry() {
        when(service.revokeAllSessions(17, 4)).thenReturn(0);
        ResponseEntity<?> response = controller().revokeAll(userAuth(17, "Jia-A", 4), new MockHttpServletRequest());
        assertConflict(response, "SESSION_EPOCH_CONFLICT");
        verify(service, times(1)).revokeAllSessions(17, 4);
        verifyNoMoreInteractions(service);
    }

    @Test
    void maxEpochConflictsWithoutWriteAndLegacySubjectIsServerResolvedAtEpochZero() {
        ResponseEntity<?> exhausted = controller().revokeAll(userAuth(17, "Jia-A", Long.MAX_VALUE),
                new MockHttpServletRequest());
        assertConflict(exhausted, "AUTH_EPOCH_EXHAUSTED");
        verifyNoInteractions(service);

        reset(service);
        when(service.findUniqueByExactJiacn("Legacy-A")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(22, "Legacy-A", AccountState.ACTIVE, 0)));
        when(service.revokeAllSessions(22, 0)).thenReturn(1);
        ResponseEntity<?> legacy = controller().revokeAll(legacyUserAuth("Legacy-A"), new MockHttpServletRequest());
        assertEquals(204, legacy.getStatusCode().value());
        verify(service).findUniqueByExactJiacn("Legacy-A");
        verify(service).revokeAllSessions(22, 0);
    }

    @Test
    void rejectsMachineSessionParametersAndBodyWithZeroWrites() {
        assertEquals(403, controller().revokeAll(machineAuth(), new MockHttpServletRequest()).getStatusCode().value());
        assertEquals(403, controller().revokeAll(
                new UsernamePasswordAuthenticationToken("session", "n/a", AuthorityUtils.NO_AUTHORITIES),
                new MockHttpServletRequest()).getStatusCode().value());

        MockHttpServletRequest query = new MockHttpServletRequest();
        query.addParameter("uid", "999");
        assertEquals(400, controller().revokeAll(userAuth(17, "Jia-A", 4), query).getStatusCode().value());

        MockHttpServletRequest body = new MockHttpServletRequest();
        body.setContent("{\"uid\":999}".getBytes());
        assertEquals(400, controller().revokeAll(userAuth(17, "Jia-A", 4), body).getStatusCode().value());
        verifyNoInteractions(service);
    }

    private SessionRevocationController controller() {
        return new SessionRevocationController(service);
    }

    private static void assertConflict(ResponseEntity<?> response, String code) {
        assertEquals(409, response.getStatusCode().value());
        assertInstanceOf(JsonResult.class, response.getBody());
        assertEquals(code, ((JsonResult<?>) response.getBody()).getCode());
    }

    private static JwtAuthenticationToken userAuth(long id, String jiacn, long epoch) {
        return auth(claims("token_kind", "user", "uid", Long.toString(id), "jiacn", jiacn,
                "username", "alice", "auth_epoch", epoch, "sub", "alice", "client_id", "web"));
    }

    private static JwtAuthenticationToken legacyUserAuth(String jiacn) {
        return auth(claims("jiacn", jiacn, "username", "legacy", "sub", "legacy", "client_id", "web"));
    }

    private static JwtAuthenticationToken machineAuth() {
        return auth(claims("token_kind", "machine", "sub", "agent", "client_id", "agent"));
    }

    private static JwtAuthenticationToken auth(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), AuthorityUtils.NO_AUTHORITIES);
    }

    private static Map<String, Object> claims(Object... pairs) {
        Map<String, Object> claims = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) claims.put((String) pairs[i], pairs[i + 1]);
        return claims;
    }
}
