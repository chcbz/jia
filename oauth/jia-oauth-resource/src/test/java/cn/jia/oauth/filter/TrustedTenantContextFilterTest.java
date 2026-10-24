package cn.jia.oauth.filter;

import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.oauth.config.ResourceServerConfig;
import cn.jia.oauth.security.TenantAdmissionPolicy;
import cn.jia.oauth.security.UserAdmissionScopeResolver;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import jakarta.servlet.ServletException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TrustedTenantContextFilterTest {

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TrustedTenantContext.clear();
    }

    @Test
    void fixedTenantUserBindsVerifiedScopeAndIgnoresAllRequestSelectors() throws Exception {
        TrustedTenantContextFilter filter = fixedFilter();
        SecurityContextHolder.getContext().setAuthentication(authentication(userClaims("1")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/conversation/content");
        request.setParameter("tenant_id", "0");
        request.addHeader("X-Tenant-ID", "0");
        request.setCookies(new Cookie("tenant_id", "0"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            BusinessScope scope = TrustedTenantContext.requireScope();
            assertEquals("1", scope.tenantId().value());
            assertEquals("cyf", scope.dataDomainId().value());
            assertEquals("jia-17", scope.ownerJiacn());
            assertEquals("public-web", scope.callerClientId());
        });

        assertEquals(200, response.getStatus());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void topLevelRequestDiscardsStaleContextAndCleansAfterDownstreamFailure() {
        TrustedTenantContextFilter filter = fixedFilter();
        SecurityContextHolder.getContext().setAuthentication(authentication(userClaims("1")));
        BusinessScope stale = new BusinessScope(TenantId.of("9"), DataDomainId.of("cyf"),
                "stale-owner", "stale-client");

        TrustedTenantContext.runWithScope(stale, () -> {
            ServletException failure = assertThrows(ServletException.class, () -> filter.doFilter(
                    new MockHttpServletRequest("GET", "/chat/conversation/content"),
                    new MockHttpServletResponse(), (request, response) -> {
                        assertEquals("1", TrustedTenantContext.requireTenantId().value());
                        throw new ServletException("boom");
                    }));
            assertEquals("boom", failure.getMessage());
            assertFalse(TrustedTenantContext.currentScope().isPresent());
        });
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void machineAndAnonymousRequestsNeverReceiveAUserTenantScope() throws Exception {
        TrustedTenantContextFilter filter = fixedFilter();
        AtomicBoolean reached = new AtomicBoolean();

        SecurityContextHolder.getContext().setAuthentication(authentication(Map.of(
                "token_kind", "machine", "sub", "machine-client", "client_id", "machine-client")));
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertFalse(TrustedTenantContext.currentScope().isPresent());
            reached.set(true);
        });
        assertTrue(reached.get());

        reached.set(false);
        SecurityContextHolder.clearContext();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertFalse(TrustedTenantContext.currentScope().isPresent());
            reached.set(true);
        });
        assertTrue(reached.get());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void isolatedLaneRejectsUnresolvableUserWithoutInvokingDownstream() throws Exception {
        TrustedTenantContextFilter filter = fixedFilter();
        SecurityContextHolder.getContext().setAuthentication(authentication(userClaims("0")));
        AtomicBoolean reached = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                (request, ignoredResponse) -> reached.set(true));

        assertEquals(403, response.getStatus());
        assertEquals("private, no-store", response.getHeader("Cache-Control"));
        assertFalse(reached.get());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void defaultPlatformLaneKeepsLegacyCompatibilityButDoesNotInventTenantZero() throws Exception {
        ResourceServerConfig.TenantDeploymentProperties properties =
                new ResourceServerConfig.TenantDeploymentProperties();
        TenantAdmissionPolicy policy = TenantAdmissionPolicy.from(properties);
        TrustedTenantContextFilter filter = new TrustedTenantContextFilter(
                new UserAdmissionScopeResolver(policy), policy);
        SecurityContextHolder.getContext().setAuthentication(authentication(userClaims(null)));
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
            assertFalse(TrustedTenantContext.currentScope().isPresent());
            assertThrows(IllegalStateException.class, TrustedTenantContext::requireTenantId);
            reached.set(true);
        });

        assertTrue(reached.get());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void defaultPlatformLegacyUserBindsOnlyAuthoritativeTenantZeroScopeAndIgnoresRequestSelectors()
            throws Exception {
        AccountSecurityService accounts = mock(AccountSecurityService.class);
        when(accounts.findUniqueByExactJiacn("Legacy-A")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(22, "Legacy-A", AccountState.ACTIVE, 0)));
        TrustedTenantContextFilter filter = compatibilityFilter(accounts);
        SecurityContextHolder.getContext().setAuthentication(authentication(legacyClaims("Legacy-A")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agent/probe");
        request.setParameter("tenant_id", "9");
        request.addHeader("X-Tenant-ID", "9");
        request.setCookies(new Cookie("tenant_id", "9"));

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
            BusinessScope scope = TrustedTenantContext.requireScope();
            assertEquals("0", scope.tenantId().value());
            assertEquals("cyf", scope.dataDomainId().value());
            assertEquals("Legacy-A", scope.ownerJiacn());
            assertEquals("public-web", scope.callerClientId());
        });

        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void legacyUserFailsClosedWithoutOneActiveEpochZeroExactAccount() throws Exception {
        AccountSecurityService accounts = mock(AccountSecurityService.class);
        TrustedTenantContextFilter filter = compatibilityFilter(accounts);
        SecurityContextHolder.getContext().setAuthentication(authentication(legacyClaims("Legacy-A")));

        for (Optional<AccountSecuritySnapshot> snapshot : java.util.List.of(
                Optional.<AccountSecuritySnapshot>empty(),
                Optional.of(new AccountSecuritySnapshot(22, "Legacy-A", AccountState.DISABLED, 0)),
                Optional.of(new AccountSecuritySnapshot(22, "Legacy-A", AccountState.ACTIVE, 1)),
                Optional.of(new AccountSecuritySnapshot(0, "Legacy-A", AccountState.ACTIVE, 0)),
                Optional.of(new AccountSecuritySnapshot(22, "legacy-a", AccountState.ACTIVE, 0)))) {
            when(accounts.findUniqueByExactJiacn("Legacy-A")).thenReturn(snapshot);
            AtomicBoolean reached = new AtomicBoolean();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest(), response,
                    (request, ignoredResponse) -> reached.set(true));
            assertEquals(403, response.getStatus());
            assertFalse(reached.get());
            assertFalse(TrustedTenantContext.currentScope().isPresent());
        }

        TrustedTenantContextFilter missingService = compatibilityFilter(null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        missingService.doFilter(new MockHttpServletRequest(), response,
                (request, ignoredResponse) -> { throw new AssertionError("must not continue"); });
        assertEquals(403, response.getStatus());
    }

    @Test
    void fixedAndStrictPlatformLanesRejectLegacyUserBeforeAccountLookup() throws Exception {
        AccountSecurityService accounts = mock(AccountSecurityService.class);
        for (TenantAdmissionPolicy policy : java.util.List.of(fixedPolicy(), strictPolicy())) {
            TrustedTenantContextFilter filter = new TrustedTenantContextFilter(
                    new UserAdmissionScopeResolver(policy, accounts), policy);
            SecurityContextHolder.getContext().setAuthentication(authentication(legacyClaims("Legacy-A")));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest(), response,
                    (request, ignoredResponse) -> { throw new AssertionError("must not continue"); });
            assertEquals(403, response.getStatus());
            assertFalse(TrustedTenantContext.currentScope().isPresent());
        }
        verifyNoInteractions(accounts);
    }

    @Test
    void legacyScopeIsClearedWhenDownstreamThrows() {
        AccountSecurityService accounts = mock(AccountSecurityService.class);
        when(accounts.findUniqueByExactJiacn("Legacy-A")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(22, "Legacy-A", AccountState.ACTIVE, 0)));
        TrustedTenantContextFilter filter = compatibilityFilter(accounts);
        SecurityContextHolder.getContext().setAuthentication(authentication(legacyClaims("Legacy-A")));

        ServletException failure = assertThrows(ServletException.class, () -> filter.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), (request, response) -> {
                    assertEquals("0", TrustedTenantContext.requireTenantId().value());
                    throw new ServletException("legacy failure");
                }));

        assertEquals("legacy failure", failure.getMessage());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    @Test
    void cleanupFilterClearsRequestAsyncAndErrorDispatchThreadsEvenWhenDownstreamStops() {
        TrustedTenantContextCleanupFilter cleanup = new TrustedTenantContextCleanupFilter();
        BusinessScope stale = new BusinessScope(TenantId.of("9"), DataDomainId.of("cyf"),
                "stale-owner", "stale-client");

        for (DispatcherType dispatcherType : new DispatcherType[] {
                DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR}) {
            TrustedTenantContext.runWithScope(stale, () -> {
                MockHttpServletRequest request = new MockHttpServletRequest();
                request.setDispatcherType(dispatcherType);
                assertThrows(ServletException.class, () -> cleanup.doFilter(request,
                        new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
                            assertFalse(TrustedTenantContext.currentScope().isPresent());
                            throw new ServletException("stop");
                        }));
                assertFalse(TrustedTenantContext.currentScope().isPresent());
            });
            assertFalse(TrustedTenantContext.currentScope().isPresent());
        }
    }

    @Test
    void nestedErrorDispatchHidesOuterRequestTenantThenRestoresOnlyOuterContinuation() {
        TrustedTenantContextCleanupFilter cleanup = new TrustedTenantContextCleanupFilter();
        BusinessScope areaOne = new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "owner-1", "public-web");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/fails");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ServletException failure = assertThrows(ServletException.class, () ->
                cleanup.doFilter(request, response, (outerRequest, outerResponse) -> {
                    assertFalse(TrustedTenantContext.currentScope().isPresent());
                    try {
                        TrustedTenantContext.runWithScopeChecked(areaOne, () -> {
                            assertEquals("1", TrustedTenantContext.requireTenantId().value());
                            request.setDispatcherType(DispatcherType.ERROR);
                            request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, "/chat/fails");
                            cleanup.doFilter(request, response, (errorRequest, errorResponse) ->
                                    assertFalse(TrustedTenantContext.currentScope().isPresent()));
                            assertEquals("1", TrustedTenantContext.requireTenantId().value());
                            cleanup.doFilter(request, response, (errorRequest, errorResponse) -> {
                                assertFalse(TrustedTenantContext.currentScope().isPresent());
                                throw new ServletException("error failure");
                            });
                        });
                    } catch (ServletException known) {
                        throw known;
                    } catch (RuntimeException unchecked) {
                        throw unchecked;
                    } catch (Exception impossible) {
                        throw new ServletException(impossible);
                    }
                }));

        assertEquals("error failure", failure.getMessage());
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    private static TrustedTenantContextFilter fixedFilter() {
        TenantAdmissionPolicy policy = fixedPolicy();
        return new TrustedTenantContextFilter(new UserAdmissionScopeResolver(policy), policy);
    }

    private static TenantAdmissionPolicy fixedPolicy() {
        ResourceServerConfig.TenantDeploymentProperties properties =
                new ResourceServerConfig.TenantDeploymentProperties();
        properties.setAdmissionMode(TenantAdmissionPolicy.Mode.FIXED_TENANT);
        properties.setFixedTenantId("1");
        return TenantAdmissionPolicy.from(properties);
    }

    private static TenantAdmissionPolicy strictPolicy() {
        ResourceServerConfig.TenantDeploymentProperties properties =
                new ResourceServerConfig.TenantDeploymentProperties();
        properties.setRequireBoundIdentity(true);
        properties.setAllowedTenants(java.util.List.of("1"));
        return TenantAdmissionPolicy.from(properties);
    }

    private static TrustedTenantContextFilter compatibilityFilter(AccountSecurityService accounts) {
        TenantAdmissionPolicy policy = TenantAdmissionPolicy.from(
                new ResourceServerConfig.TenantDeploymentProperties());
        return new TrustedTenantContextFilter(new UserAdmissionScopeResolver(policy, accounts), policy);
    }

    private static JwtAuthenticationToken authentication(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), AuthorityUtils.createAuthorityList("SCOPE_read"));
    }

    private static Map<String, Object> userClaims(String tenantId) {
        java.util.HashMap<String, Object> claims = new java.util.HashMap<>();
        claims.put("token_kind", "user");
        claims.put("uid", "17");
        claims.put("auth_epoch", 4L);
        claims.put("sub", "user-17");
        claims.put("client_id", "public-web");
        claims.put("username", "alice");
        claims.put("jiacn", "jia-17");
        if (tenantId != null) claims.put("tenant_id", tenantId);
        return claims;
    }

    private static Map<String, Object> legacyClaims(String jiacn) {
        return Map.of("sub", "legacy", "client_id", "public-web", "username", "legacy",
                "jiacn", jiacn);
    }
}
