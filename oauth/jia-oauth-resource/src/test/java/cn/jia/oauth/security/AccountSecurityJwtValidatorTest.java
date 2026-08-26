package cn.jia.oauth.security;

import cn.jia.test.BaseMockTest;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSecurityJwtValidatorTest extends BaseMockTest {
    @Mock AccountSecurityService service;

    @Test
    void validatesNewAndLegacyUsersAgainstAuthoritativeAccount() {
        AccountSecurityJwtValidator validator = new AccountSecurityJwtValidator(service);
        when(service.findByUserId(17)).thenReturn(Optional.of(active(17, "Jia-A", 4)));
        assertValid(validator, claims(
                "token_kind", "user", "uid", "17", "jiacn", "Jia-A", "username", "alice",
                "auth_epoch", 4L, "sub", "alice", "client_id", "web"));

        assertInvalid(validator, claims(
                "token_kind", "user", "uid", "17", "jiacn", "jia-a", "username", "alice",
                "auth_epoch", 4L, "sub", "alice", "client_id", "web"));
        assertInvalid(validator, claims(
                "token_kind", "user", "uid", "17", "jiacn", "Jia-A", "username", "alice",
                "auth_epoch", 3L, "sub", "alice", "client_id", "web"));

        when(service.findUniqueByExactJiacn("Legacy-A")).thenReturn(Optional.of(active(22, "Legacy-A", 0)));
        assertValid(validator, claims("jiacn", "Legacy-A", "username", "legacy", "sub", "legacy", "client_id", "web"));
        when(service.findUniqueByExactJiacn("Legacy-A")).thenReturn(Optional.of(active(22, "Legacy-A", 1)));
        assertInvalid(validator, claims("jiacn", "Legacy-A", "username", "legacy", "sub", "legacy", "client_id", "web"));
    }

    @Test
    void acceptsOnlyExplicitOrStructurallyStrictMachineTokensWithoutDatabaseReads() {
        AccountSecurityJwtValidator validator = new AccountSecurityJwtValidator(service);
        assertValid(validator, claims("token_kind", "machine", "sub", "agent-client", "client_id", "agent-client"));
        assertValid(validator, claims("sub", "legacy-client", "client_id", "legacy-client", "scope", List.of("read")));

        assertInvalid(validator, claims("token_kind", "machine", "sub", "agent-client", "client_id", "agent-client",
                "jiacn", "Jia-A"));
        assertInvalid(validator, claims("sub", "subject", "client_id", "different"));
        assertInvalid(validator, claims("sub", "subject", "client_id", "subject", "username", "alice"));
        assertInvalid(validator, claims("client_id", "subject"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsAmbiguousKindsAndEveryNonCanonicalUidOrEpochWireType() {
        AccountSecurityJwtValidator validator = new AccountSecurityJwtValidator(service);
        for (Object uid : List.of("0", "01", "+1", "-1", " 1", "9223372036854775808")) {
            assertInvalid(validator, userClaims(uid, 0L));
        }
        for (Object epoch : List.of("0", -1L, 1.0d, new BigDecimal("1.0"),
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))) {
            assertInvalid(validator, userClaims("17", epoch));
        }
        assertInvalid(validator, claims("token_kind", "USER", "sub", "alice", "client_id", "web"));
        assertInvalid(validator, claims("token_kind", 1, "sub", "alice", "client_id", "web"));
        assertInvalid(validator, claims("uid", "17", "jiacn", "Jia-A", "sub", "alice", "client_id", "web"));
        assertInvalid(validator, claims("jiacn", "Jia-A", "auth_epoch", 0L, "sub", "alice", "client_id", "web"));
        assertInvalid(validator, claims("token_kind", "user", "uid", "17", "jiacn", " ", "username", "alice",
                "auth_epoch", 0L, "sub", "alice", "client_id", "web"));
    }

    @Test
    void unknownStateAndDatabaseFailureFailClosed() {
        AccountSecurityJwtValidator validator = new AccountSecurityJwtValidator(service);
        when(service.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.UNKNOWN, 0)));
        assertInvalid(validator, userClaims("17", 0L));

        when(service.findByUserId(17)).thenThrow(new IllegalStateException("database unavailable"));
        assertInvalid(validator, userClaims("17", 0L));
    }

    private static AccountSecuritySnapshot active(long id, String jiacn, long epoch) {
        return new AccountSecuritySnapshot(id, jiacn, AccountState.ACTIVE, epoch);
    }

    private static Map<String, Object> userClaims(Object uid, Object epoch) {
        return claims("token_kind", "user", "uid", uid, "jiacn", "Jia-A", "username", "alice",
                "auth_epoch", epoch, "sub", "alice", "client_id", "web");
    }

    private static Map<String, Object> claims(Object... pairs) {
        Map<String, Object> claims = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            claims.put((String) pairs[i], pairs[i + 1]);
        }
        return claims;
    }

    private static void assertValid(AccountSecurityJwtValidator validator, Map<String, Object> claims) {
        assertFalse(validator.validate(jwt(claims)).hasErrors(), claims.toString());
    }

    private static void assertInvalid(AccountSecurityJwtValidator validator, Map<String, Object> claims) {
        assertTrue(validator.validate(jwt(claims)).hasErrors(), claims.toString());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
