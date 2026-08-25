package cn.jia.oauth.config;

import cn.jia.core.redis.ThirdPartyLoginTransactionService;
import cn.jia.oauth.api.OauthController;
import cn.jia.oauth.service.ClientService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationConsentAuthenticationConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.ModelAndView;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OauthConsentTemplateTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void consentFormPostsStandardSpringAuthorizationServerFields() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/templates/oauth/authorize.html")) {
            assertNotNull(input);
            String html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("method=\"post\" action=\"/oauth2/authorize\""));
            assertTrue(html.contains("name=\"client_id\" th:value=\"${clientId}\""));
            assertTrue(html.contains("name=\"state\" th:value=\"${state}\""));
            assertTrue(html.contains("name=\"scope\" th:value=\"${item}\""));
            assertFalse(html.contains("name=\"user_oauth_approval\""));
            assertFalse(html.contains("th:name=\"'scope.'+${item}\""));
            assertFalse(html.contains("method=\"post\" action=\"authorize\""));
        }
    }

    @Test
    void springAuthorizationServerConsentConverterAcceptsRenderedFieldContract() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/authorize");
        request.setContentType("application/x-www-form-urlencoded");
        request.addParameter("client_id", "public-web");
        request.addParameter("state", "consent-state");
        request.addParameter("scope", "openid", "profile");

        Authentication converted = new OAuth2AuthorizationConsentAuthenticationConverter().convert(request);

        OAuth2AuthorizationConsentAuthenticationToken consent = assertInstanceOf(
                OAuth2AuthorizationConsentAuthenticationToken.class, converted);
        assertEquals("public-web", consent.getClientId());
        assertEquals("consent-state", consent.getState());
        assertEquals(Set.of("openid", "profile"), consent.getScopes());
    }

    @Test
    void controllerBuildsConsentModelFromStandardQueryParametersWithoutNimbusSessionModel() {
        OauthController controller = new OauthController(
                mock(ClientService.class), mock(UserService.class), mock(AccountSecurityService.class),
                mock(PermsService.class), mock(RestTemplate.class), mock(ThirdPartyLoginTransactionService.class));

        ModelAndView view = controller.getAccessConfirmation(
                "public-web", "consent-state", "openid profile openid");

        assertEquals("oauth/authorize", view.getViewName());
        assertEquals("public-web", view.getModel().get("clientId"));
        assertEquals("consent-state", view.getModel().get("state"));
        assertEquals(List.of("openid", "profile"), view.getModel().get("scopes"));
    }
}
