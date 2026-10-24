package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.oauth.config.ResourceServerConfig.TenantDeploymentProperties;
import cn.jia.test.BaseMockTest;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    @Mock TenantAuthorizationVerifier tenantAuthorizationVerifier;
    @Mock AccountSecurityService service;

    @Test
    void fixedTenantAcceptsCurrentBasicUserWithoutPlatformVerifierAndBuildsBasicScope() {
        TenantAdmissionPolicy policy = fixedTenantPolicy("1");
        Map<String, Object> user = userClaims("17", 4L);
        user.put("tenant_id", "1");
        user.put("tenantId", "1");
        user.put("tenant_claim_version", "1");
        when(service.findByUserId(17)).thenReturn(Optional.of(active(17, "Jia-A", 4)));

        assertValid(new AccountSecurityJwtValidator(service, null, policy), user);
        assertFalse(new TenantDeploymentTokenValidator(policy).validate(jwt(user)).hasErrors());
        var scope = new UserAdmissionScopeResolver(policy)
                .resolve(new JwtAuthenticationToken(jwt(user), List.of()));

        assertEquals("1", scope.businessScope().tenantId().value());
        assertEquals("cyf", scope.businessScope().dataDomainId().value());
        assertEquals("Jia-A", scope.businessScope().ownerJiacn());
        assertEquals("web", scope.businessScope().callerClientId());
        assertTrue(scope.platformVersions().isEmpty());
        verifyNoInteractions(tenantAuthorizationVerifier);
    }

    @Test
    void fixedTenantRejectsMissingForeignNonCanonicalMachineAndPartialPlatformClaims() {
        TenantAdmissionPolicy policy = fixedTenantPolicy("1");
        AccountSecurityJwtValidator account = new AccountSecurityJwtValidator(service, null, policy);
        TenantDeploymentTokenValidator deployment = new TenantDeploymentTokenValidator(policy);
        when(service.findByUserId(17)).thenReturn(Optional.of(active(17, "Jia-A", 4)));

        for (String tenant : List.of("0", "01")) {
            Map<String, Object> user = userClaims("17", 4L);
            user.put("tenant_id", tenant);
            assertInvalid(account, user);
            assertTrue(deployment.validate(jwt(user)).hasErrors());
        }
        Map<String, Object> missing = userClaims("17", 4L);
        assertInvalid(account, missing);
        assertTrue(deployment.validate(jwt(missing)).hasErrors());

        Map<String, Object> partial = userClaims("17", 4L);
        partial.put("tenant_id", "1");
        partial.put("tenant_name", "华南测试区");
        assertInvalid(account, partial);
        assertTrue(deployment.validate(jwt(partial)).hasErrors());

        Map<String, Object> machine = claims(
                "token_kind", "machine", "sub", "agent-client", "client_id", "agent-client");
        assertTrue(deployment.validate(jwt(machine)).hasErrors());
        assertThrows(AccessDeniedException.class, () -> new UserAdmissionScopeResolver(policy)
                .resolve(new JwtAuthenticationToken(jwt(machine), List.of())));
    }

    @Test
    void fixedTenantRejectsConflictingDomainAliasVersionAndAliasOnlyClaimsAcrossAllBoundaries() {
        TenantAdmissionPolicy policy = fixedTenantPolicy("1");
        AccountSecurityJwtValidator account = new AccountSecurityJwtValidator(service, null, policy);
        TenantDeploymentTokenValidator deployment = new TenantDeploymentTokenValidator(policy);
        UserAdmissionScopeResolver resolver = new UserAdmissionScopeResolver(policy);
        when(service.findByUserId(17)).thenReturn(Optional.of(active(17, "Jia-A", 4)));

        Map<String, Object> foreignDomain = userClaims("17", 4L);
        foreignDomain.putAll(tenantBundle());
        foreignDomain.put("data_domain_id", "other");
        assertRejectedEverywhere(account, deployment, resolver, foreignDomain);

        Map<String, Object> conflictingAlias = userClaims("17", 4L);
        conflictingAlias.put("tenant_id", "1");
        conflictingAlias.put("tenantId", "0");
        assertRejectedEverywhere(account, deployment, resolver, conflictingAlias);

        Map<String, Object> unknownVersion = userClaims("17", 4L);
        unknownVersion.put("tenant_id", "1");
        unknownVersion.put("tenant_claim_version", "2");
        assertRejectedEverywhere(account, deployment, resolver, unknownVersion);

        Map<String, Object> aliasOnly = userClaims("17", 4L);
        aliasOnly.put("tenantId", "1");
        aliasOnly.put("tenant_claim_version", "1");
        assertRejectedEverywhere(account, deployment, resolver, aliasOnly);

        Map<String, Object> nullCanonical = userClaims("17", 4L);
        nullCanonical.put("tenant_id", null);
        nullCanonical.put("tenantId", "1");
        nullCanonical.put("tenant_claim_version", "1");
        assertRejectedEverywhere(account, deployment, resolver, nullCanonical);
    }

    @Test
    void fixedTenantStillRejectsInactiveOrRevokedAccounts() {
        TenantAdmissionPolicy policy = fixedTenantPolicy("1");
        Map<String, Object> user = userClaims("17", 4L);
        user.put("tenant_id", "1");
        AccountSecurityJwtValidator validator = new AccountSecurityJwtValidator(service, null, policy);

        when(service.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.SUSPENDED, 4)));
        assertInvalid(validator, user);
        when(service.findByUserId(17)).thenReturn(Optional.of(active(17, "Jia-A", 5)));
        assertInvalid(validator, user);
    }

    @Test
    void resolvesOnlyAuthenticatedBoundUserScopeForTransactionalAdmission() {
        var resolver = new UserAdmissionScopeResolver();
        Map<String, Object> user = userClaims("17", 4L);
        user.putAll(tenantBundle());
        var selected = resolver.resolve(new JwtAuthenticationToken(jwt(user), List.of()));
        assertEquals("1", selected.businessScope().tenantId().value());
        assertEquals("cyf", selected.businessScope().dataDomainId().value());
        assertEquals("Jia-A", selected.businessScope().ownerJiacn());
        assertEquals("web", selected.businessScope().callerClientId());
        assertEquals(17, selected.userId());
        assertEquals(4, selected.authEpoch());
        assertEquals(2, selected.tenantSessionEpoch());
        assertEquals(3, selected.membershipRevision());
        assertEquals(4, selected.clientGrantRevision());
        assertThrows(AccessDeniedException.class, () -> resolver.resolve(null));
        user.remove("tenant_id");
        assertThrows(AccessDeniedException.class, () -> resolver.resolve(new JwtAuthenticationToken(jwt(user), List.of())));
        user.put("tenant_id", "01");
        assertThrows(AccessDeniedException.class, () -> resolver.resolve(new JwtAuthenticationToken(jwt(user), List.of())));
        Map<String, Object> machine = claims("token_kind", "machine", "sub", "web", "client_id", "web");
        assertThrows(AccessDeniedException.class, () -> resolver.resolve(new JwtAuthenticationToken(jwt(machine), List.of())));
    }

    @Test
    void rejectsUnboundNewAndLegacyUsersEvenWhenAccountIsCurrent() {
        AccountSecurityJwtValidator validator = new AccountSecurityJwtValidator(service);
        when(service.findByUserId(17)).thenReturn(Optional.of(active(17, "Jia-A", 4)));
        assertInvalid(validator, claims(
                "token_kind", "user", "uid", "17", "jiacn", "Jia-A", "username", "alice",
                "auth_epoch", 4L, "sub", "alice", "client_id", "web"));

        assertInvalid(validator, claims(
                "token_kind", "user", "uid", "17", "jiacn", "jia-a", "username", "alice",
                "auth_epoch", 4L, "sub", "alice", "client_id", "web"));
        assertInvalid(validator, claims(
                "token_kind", "user", "uid", "17", "jiacn", "Jia-A", "username", "alice",
                "auth_epoch", 3L, "sub", "alice", "client_id", "web"));

        when(service.findUniqueByExactJiacn("Legacy-A")).thenReturn(Optional.of(active(22, "Legacy-A", 0)));
        assertInvalid(validator, claims("jiacn", "Legacy-A", "username", "legacy", "sub", "legacy", "client_id", "web"));
        when(service.findUniqueByExactJiacn("Legacy-A")).thenReturn(Optional.of(active(22, "Legacy-A", 1)));
        assertInvalid(validator, claims("jiacn", "Legacy-A", "username", "legacy", "sub", "legacy", "client_id", "web"));
    }

    @Test
    void compatibilityDeploymentAcceptsOnlyCurrentLegacyTenantZeroAccount() {
        AccountSecurityJwtValidator compatibility =
                new AccountSecurityJwtValidator(service, null, false);
        when(service.findUniqueByExactJiacn("Legacy-A"))
                .thenReturn(Optional.of(active(22, "Legacy-A", 0)));
        assertValid(compatibility, claims(
                "jiacn", "Legacy-A", "username", "legacy", "sub", "legacy", "client_id", "web"));

        when(service.findUniqueByExactJiacn("Legacy-A"))
                .thenReturn(Optional.of(active(22, "Legacy-A", 1)));
        assertInvalid(compatibility, claims(
                "jiacn", "Legacy-A", "username", "legacy", "sub", "legacy", "client_id", "web"));

        Map<String, Object> forgedArea = tenantBundle();
        forgedArea.putAll(claims(
                "jiacn", "Legacy-A", "username", "legacy", "sub", "legacy", "client_id", "web"));
        assertInvalid(compatibility, forgedArea);
    }

    @Test
    void rejectsTenantIdentityOnMachineAndLegacyTokens() {
        Map<String, Object> tenantMachine = tenantBundle();
        tenantMachine.putAll(claims("token_kind", "machine", "sub", "agent-client", "client_id", "agent-client"));
        assertInvalid(new AccountSecurityJwtValidator(service), tenantMachine);
        Map<String, Object> tenantLegacyMachine = tenantBundle();
        tenantLegacyMachine.putAll(claims("sub", "agent-client", "client_id", "agent-client"));
        assertInvalid(new AccountSecurityJwtValidator(service), tenantLegacyMachine);
        Map<String, Object> tenantLegacyUser = tenantBundle();
        tenantLegacyUser.putAll(claims("sub", "alice", "client_id", "web", "jiacn", "Jia-A"));
        assertInvalid(new AccountSecurityJwtValidator(service), tenantLegacyUser);
        Map<String, Object> tenantUser = tenantBundle();
        tenantUser.putAll(userClaims("17", 4L));
        when(service.findByUserId(17)).thenReturn(Optional.of(active(17, "Jia-A", 4)));
        assertInvalid(new AccountSecurityJwtValidator(service), tenantUser);
        TenantAuthorizationAttributes binding = new TenantAuthorizationAttributes(
                TenantId.of("1"), DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "2", "3", "4");
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web")).thenReturn(true);
        assertValid(new AccountSecurityJwtValidator(service, tenantAuthorizationVerifier), tenantUser);
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web")).thenReturn(false);
        assertInvalid(new AccountSecurityJwtValidator(service, tenantAuthorizationVerifier), tenantUser);
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web"))
                .thenThrow(new IllegalStateException("authoritative store unavailable"));
        assertInvalid(new AccountSecurityJwtValidator(service, tenantAuthorizationVerifier), tenantUser);
        tenantUser.remove("data_domain_id");
        assertInvalid(new AccountSecurityJwtValidator(service), tenantUser);
        tenantUser.put("data_domain_id", "cyf");
        tenantUser.put("client_tenant_grant_revision", "9223372036854775808");
        assertInvalid(new AccountSecurityJwtValidator(service), tenantUser);
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

    private static Map<String, Object> tenantBundle() {
        return claims("tenant_id", "1",
                "data_domain_id", "cyf",
                "tenant_name", "华南测试区",
                "tenant_kind", "TEST",
                "tenant_session_epoch", "2",
                "membership_revision", "3",
                "client_tenant_grant_revision", "4");
    }

    private static AccountSecuritySnapshot active(long id, String jiacn, long epoch) {
        return new AccountSecuritySnapshot(id, jiacn, AccountState.ACTIVE, epoch);
    }

    private static TenantAdmissionPolicy fixedTenantPolicy(String tenantId) {
        TenantDeploymentProperties properties = new TenantDeploymentProperties();
        properties.setAdmissionMode(TenantAdmissionPolicy.Mode.FIXED_TENANT);
        properties.setFixedTenantId(tenantId);
        return TenantAdmissionPolicy.from(properties);
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

    private static void assertRejectedEverywhere(AccountSecurityJwtValidator account,
                                                 TenantDeploymentTokenValidator deployment,
                                                 UserAdmissionScopeResolver resolver,
                                                 Map<String, Object> claims) {
        assertInvalid(account, claims);
        assertTrue(deployment.validate(jwt(claims)).hasErrors(), claims.toString());
        assertThrows(AccessDeniedException.class,
                () -> resolver.resolve(new JwtAuthenticationToken(jwt(claims), List.of())),
                claims.toString());
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
