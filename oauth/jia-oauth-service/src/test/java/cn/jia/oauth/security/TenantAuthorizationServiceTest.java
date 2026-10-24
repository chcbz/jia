package cn.jia.oauth.security;

import cn.jia.core.tenant.TenantId;
import cn.jia.user.entity.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TenantAuthorizationServiceTest {
    private final TenantAuthorizationService service = new TenantAuthorizationService();
    private final RegisteredClient client = RegisteredClient.withId("local-client")
            .clientId("cyf-local")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://127.0.0.1:10018/oauth2/callback").scope("openid").build();

    @Test
    void noDatabaseIsNeededAndCredentialsAreNotRetained() {
        OAuth2Authorization saved = saveStage();
        Authentication login = saved.getAttribute(Principal.class.getName());
        assertNull(login.getCredentials());
        CustomUserDetails user = (CustomUserDetails) login.getPrincipal();
        assertEquals(17, user.getUserId());
        assertEquals("<unavailable>", user.getPassword());
        assertEquals(new FixedTenantAuthorizationBinding(TenantId.of("1"), "cyf-local"),
                saved.getAttribute(FixedTenantAuthorizationBinding.attributeName()));
    }

    @Test
    void staleSaveAndRemoveCannotOverwriteNewerStateOrChangeTenant() {
        OAuth2Authorization staged = saveStage();
        service.save(withCode(staged, "current-code"));
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> service.save(staged)).getError().getErrorCode());
        assertThrows(OAuth2AuthenticationException.class, () -> service.remove(staged));
        OAuth2Authorization current = service.findById(staged.getId());
        assertThrows(IllegalArgumentException.class, () -> service.save(OAuth2Authorization.from(current)
                .attribute(FixedTenantAuthorizationBinding.attributeName(),
                        new FixedTenantAuthorizationBinding(TenantId.of("0"), "cyf-local")).build()));
        assertNotNull(service.findByToken("current-code", new OAuth2TokenType("code")));
        service.remove(current);
        assertNull(service.findById(staged.getId()));
    }

    @Test
    void concurrentWritesAcceptOnlyOneRevision() throws Exception {
        OAuth2Authorization staged = saveStage();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> saveAfter(start, withCode(staged, "code-a")));
            var second = workers.submit(() -> saveAfter(start, withCode(staged, "code-b")));
            start.countDown();
            int successes = (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertNotEquals(service.findByToken("code-a", new OAuth2TokenType("code")) == null,
                    service.findByToken("code-b", new OAuth2TokenType("code")) == null);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void anotherInstanceCannotResumeCodeOrRefreshState() {
        OAuth2Authorization staged = saveStage();
        Instant now = Instant.now();
        service.save(OAuth2Authorization.from(withCode(staged, "local-code"))
                .refreshToken(new OAuth2RefreshToken("local-refresh", now, now.plusSeconds(600))).build());
        assertNotNull(service.findByToken("local-refresh", OAuth2TokenType.REFRESH_TOKEN));
        TenantAuthorizationService restarted = new TenantAuthorizationService();
        assertNull(restarted.findById(staged.getId()));
        assertNull(restarted.findByToken("local-code", new OAuth2TokenType("code")));
        assertNull(restarted.findByToken("local-refresh", OAuth2TokenType.REFRESH_TOKEN));
    }

    @Test
    void removingCompletedAuthorizationCannotExposeEarlierCodeState() {
        OAuth2Authorization staged = saveStage();
        service.save(withCode(staged, "redeemed-code"));
        OAuth2Authorization coded = service.findById(staged.getId());
        Instant now = Instant.now();
        service.save(OAuth2Authorization.from(coded).accessToken(new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(300))).build());
        service.remove(service.findById(staged.getId()));
        assertNull(service.findById(staged.getId()));
        assertNull(service.findByToken("redeemed-code", new OAuth2TokenType("code")));
        assertNull(service.findByToken("access", OAuth2TokenType.ACCESS_TOKEN));
    }

    private OAuth2Authorization saveStage() {
        CustomUserDetails user = new CustomUserDetails(17, "Jia-A", 4, "alice", "private-password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("http://127.0.0.1:10019/oauth2/authorize").clientId("cyf-local")
                .redirectUri("http://127.0.0.1:10018/oauth2/callback").scope("openid").state("state").build();
        OAuth2Authorization staged = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        user, "private-credentials", user.getAuthorities()))
                .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .attribute(FixedTenantAuthorizationBinding.attributeName(),
                        new FixedTenantAuthorizationBinding(TenantId.of("1"), "cyf-local")).build();
        service.save(staged);
        return service.findById(staged.getId());
    }

    private static OAuth2Authorization withCode(OAuth2Authorization authorization, String value) {
        Instant now = Instant.now();
        return OAuth2Authorization.from(authorization)
                .token(new OAuth2AuthorizationCode(value, now, now.plusSeconds(300))).build();
    }

    private boolean saveAfter(CountDownLatch start, OAuth2Authorization authorization) throws InterruptedException {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            service.save(authorization);
            return true;
        } catch (OAuth2AuthenticationException stale) {
            assertEquals("invalid_grant", stale.getError().getErrorCode());
            return false;
        }
    }
}
