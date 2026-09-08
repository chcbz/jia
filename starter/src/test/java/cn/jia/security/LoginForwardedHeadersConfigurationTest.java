package cn.jia.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local servlet context only: no application scan, server startup, DB, or network. */
class LoginForwardedHeadersConfigurationTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            // Load the actual starter root file, never the dependency's same-named resource.
            // The only test override selects the file; it does not supply framework or voice values.
            .withPropertyValues("spring.config.location="
                    + Path.of("src/main/resources/application.properties").toAbsolutePath().toUri())
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TomcatServletWebServerAutoConfiguration.class));

    @Test
    void rootConfigurationPreservesVoiceDisablementAndProducesHttpsLoginRedirect() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("framework", context.getEnvironment().getProperty("server.forward-headers-strategy"));
            assertEquals("none", context.getEnvironment().getProperty("spring.ai.model.audio.transcription"));
            assertEquals("none", context.getEnvironment().getProperty("spring.ai.model.audio.speech"));
            FilterRegistrationBean<?> registration = context.getBean("forwardedHeaderFilter", FilterRegistrationBean.class);
            ForwardedHeaderFilter filter = assertInstanceOf(ForwardedHeaderFilter.class, registration.getFilter());
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
        });
    }

    @Test
    void absentForwardedHeadersDoNotInventHttpsForDirectHttp() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            FilterRegistrationBean<?> registration = context.getBean("forwardedHeaderFilter", FilterRegistrationBean.class);
            ForwardedHeaderFilter filter = assertInstanceOf(ForwardedHeaderFilter.class, registration.getFilter());
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
        });
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
