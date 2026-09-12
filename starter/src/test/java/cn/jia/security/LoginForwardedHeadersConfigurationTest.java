package cn.jia.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual classpath root configuration and servlet mocks only; no container, DB, or network. */
class LoginForwardedHeadersConfigurationTest {
    @Test
    void rootConfigurationPreservesVoiceDisablementAndProducesHttpsLoginRedirect() throws Exception {
        ForwardedHeaderFilter filter = rootConfiguredFilter();
        MockHttpServletRequest request = backendRequest();
        request.addHeader("X-Forwarded-Host", "api.chaoyoufan.cn");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (forwardedRequest, forwardedResponse) -> {
            HttpServletRequest http = (HttpServletRequest) forwardedRequest;
            assertEquals("https", http.getScheme());
            assertTrue(http.isSecure());
            assertEquals(443, http.getServerPort());
            new LoginUrlAuthenticationEntryPoint("/login/index.html").commence(
                    http, (HttpServletResponse) forwardedResponse,
                    new InsufficientAuthenticationException("anonymous regression request"));
        });

        assertEquals(302, response.getStatus());
        assertEquals("https://api.chaoyoufan.cn/login/index.html", response.getRedirectedUrl());
    }

    @Test
    void absentForwardedHeadersDoNotInventHttpsForDirectHttp() throws Exception {
        ForwardedHeaderFilter filter = rootConfiguredFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(backendRequest(), response, (directRequest, directResponse) -> {
            HttpServletRequest http = (HttpServletRequest) directRequest;
            assertEquals("http", http.getScheme());
            assertFalse(http.isSecure());
            assertEquals(10018, http.getServerPort());
            new LoginUrlAuthenticationEntryPoint("/login/index.html").commence(
                    http, (HttpServletResponse) directResponse,
                    new InsufficientAuthenticationException("anonymous direct request"));
        });

        assertEquals(302, response.getStatus());
        assertEquals("/login/index.html", response.getRedirectedUrl());
    }

    private static ForwardedHeaderFilter rootConfiguredFilter() throws IOException {
        // Read the same first classpath resource as Boot; never inject test property values.
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        assertEquals("framework", properties.getProperty("server.forward-headers-strategy"));
        assertEquals("none", properties.getProperty("spring.ai.model.audio.transcription"));
        assertEquals("none", properties.getProperty("spring.ai.model.audio.speech"));
        return new ForwardedHeaderFilter();
    }

    private static MockHttpServletRequest backendRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setScheme("http");
        request.setServerName("api.chaoyoufan.cn");
        request.setServerPort(10018);
        request.addHeader("Host", "api.chaoyoufan.cn");
        return request;
    }
}
