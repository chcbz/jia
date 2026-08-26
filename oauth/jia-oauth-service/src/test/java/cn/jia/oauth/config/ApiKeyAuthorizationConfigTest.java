package cn.jia.oauth.config;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.oauth.entity.ApiKeyAuthToken;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.test.BaseMockTest;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyAuthorizationConfigTest extends BaseMockTest {
    @Mock ApiKeyService apiKeyService;
    @Mock AccountSecurityService accountSecurityService;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        EsContextHolder.clearContext();
    }

    @Test
    void activeExactOwnerAuthenticatesAndOverwritesCookieContext() throws Exception {
        when(apiKeyService.findByApiKey("key")).thenReturn(apiKey("Jia-A"));
        when(accountSecurityService.findUniqueByExactJiacn("Jia-A")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 8)));
        ApiKeyAuthorizationConfig config = new ApiKeyAuthorizationConfig(apiKeyService, accountSecurityService);
        Filter filter = ReflectionTestUtils.invokeMethod(config, "apiKeyAuthFilter");

        EsContext stale = EsContextHolder.getContext();
        stale.setClientId("cookie-client");
        stale.setAppcn("cookie-app");
        stale.setJiacn("cookie-user");
        stale.setUsername("cookie-name");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/task");
        request.addHeader("X-API-Key", "key");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertInstanceOf(ApiKeyAuthToken.class, SecurityContextHolder.getContext().getAuthentication());
            assertEquals("client", EsContextHolder.getContext().getClientId());
            assertEquals("Jia-A", EsContextHolder.getContext().getJiacn());
            assertNull(EsContextHolder.getContext().getAppcn());
            assertNull(EsContextHolder.getContext().getUsername());
        });
    }

    @Test
    void inactiveMissingOrCaseMismatchedOwnerFailsAuthentication() {
        when(apiKeyService.findByApiKey("key")).thenReturn(apiKey("Jia-A"));
        AuthenticationProvider provider = provider();

        when(accountSecurityService.findUniqueByExactJiacn("Jia-A")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.DISABLED, 1)));
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(new ApiKeyAuthToken("key")));

        when(accountSecurityService.findUniqueByExactJiacn("Jia-A")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "jia-a", AccountState.ACTIVE, 1)));
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(new ApiKeyAuthToken("key")));

        when(accountSecurityService.findUniqueByExactJiacn("Jia-A")).thenReturn(Optional.empty());
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(new ApiKeyAuthToken("key")));
    }

    private AuthenticationProvider provider() {
        return ReflectionTestUtils.invokeMethod(
                new ApiKeyAuthorizationConfig(apiKeyService, accountSecurityService), "apiKeyAuthProvider");
    }

    private static OauthApiKeyEntity apiKey(String jiacn) {
        return new OauthApiKeyEntity().setApiKey("key").setClientId("client").setJiacn(jiacn).setStatus(1);
    }
}
