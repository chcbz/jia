package cn.jia.oauth.api;

import cn.jia.oauth.security.FixedTenantConsentService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixedTenantConsentControllerTest {
    @Test
    void consentAssetsAreRootRelativeAtBothOauthRoutes() throws Exception {
        try (var in=getClass().getResourceAsStream("/templates/oauth/authorize.html")) {
            var html=new String(java.util.Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(html.contains("src=\"js/"));assertFalse(html.contains("href=\"css/"));
            for(String path:List.of("js/jquery.min.js","js/jquery.easyui.min.js","js/easyui-lang-zh_CN.js",
                    "css/common.css","css/icon.css","css/themes/default/easyui.css","css/themes/icon.css","image/png.png")) {
                assertTrue(html.contains("@{/oauth/"+path+"}"));
                assertTrue(getClass().getResource("/static/oauth/"+path)!=null,"asset must exist: "+path);
            }
        }
    }
    @Test
    void consentPageKeepsPostOriginWithoutIncludingStateInReferrer() {
        FixedTenantConsentService service = mock(FixedTenantConsentService.class);
        Authentication login = mock(Authentication.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        var session = request.getSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(service.view(session, "consent-state", login)).thenReturn(
                new FixedTenantConsentService.Consent("cyf-local", "consent-state", List.of("openid")));

        var view = new FixedTenantConsentController(service).page("consent-state", request, response, login);

        assertEquals("strict-origin", response.getHeader("Referrer-Policy"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertEquals("oauth/authorize", view.getViewName());
        assertEquals("cyf-local", view.getModel().get("clientId"));
        assertEquals("consent-state", view.getModel().get("state"));
        assertEquals(List.of("openid"), view.getModel().get("scopes"));
        verify(service).view(session, "consent-state", login);
    }
}
