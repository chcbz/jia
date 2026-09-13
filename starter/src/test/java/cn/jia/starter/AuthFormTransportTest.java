package cn.jia.starter;

import cn.jia.base.filter.UriAccessLogFilter;
import cn.jia.user.api.LoginController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import java.util.List;
import cn.jia.base.service.LogService;
import cn.jia.base.service.impl.LogServiceImpl;
import cn.jia.core.audit.AuditDispatcher;
import cn.jia.core.common.EsRequestWrapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.DelegatingFilterProxy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real Servlet parsing + audit + Spring Security; no production users, database or external providers. */
class AuthFormTransportTest {
    @Test
    void passwordLoginAndPublicClientPkceExchangeSurviveAuditFilter(@TempDir Path temp) throws Exception {
        LogService logs = mock(LogService.class);
        var sanitizer = new LogServiceImpl();
        when(logs.captureLog(any(EsRequestWrapper.class))).thenAnswer(invocation -> {
            var record = sanitizer.captureLog(invocation.getArgument(0));
            assertNull(record.getParam(), "credential endpoint audit must omit all parameters");
            return record;
        });
        AuditDispatcher dispatcher = mock(AuditDispatcher.class);
        var factory = new TomcatServletWebServerFactory(0);
        factory.setBaseDirectory(temp.resolve("tomcat").toFile());
        var context = new AnnotationConfigWebApplicationContext();
        var server = factory.getWebServer(servlets -> {
            context.setServletContext(servlets);
            context.register(FixtureSecurity.class);
            context.refresh();
            servlets.addFilter("encoding", new CharacterEncodingFilter("UTF-8", true))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            servlets.addFilter("audit", new UriAccessLogFilter(logs, dispatcher))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            servlets.addFilter("security", new DelegatingFilterProxy("springSecurityFilterChain", context))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            servlets.addServlet("fallback", new HttpServlet() {}).addMapping("/");
        });
        try {
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            var browser = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
            String verifier = "a".repeat(86);
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String authorize = base + "/oauth2/authorize?response_type=code&client_id=fixture-web&scope=profile"
                    + "&redirect_uri=" + encode("https://client.example/callback")
                    + "&state=fixture-state&code_challenge_method=S256&code_challenge=" + challenge;
            var unauthenticated = get(browser, authorize);
            assertEquals(302, unauthenticated.statusCode());
            assertTrue(unauthenticated.headers().firstValue("location").orElseThrow().contains("/login"));
            var page = get(browser, base + "/login");
            assertEquals(200, page.statusCode());
            var csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
            assertTrue(csrf.find(), "generated login form must include CSRF token");
            String token = csrf.group(1);
            var rejected = post(browser, base + "/login", "username=" + encode("宋江")
                    + "&password=wrong-fixture-password&_csrf=" + encode(token));
            assertEquals(302, rejected.statusCode());
            assertTrue(rejected.headers().firstValue("location").orElseThrow().contains("error"));
            var login = post(browser, base + "/login", "username=" + encode("宋江")
                    + "&password=" + encode("fixture+pass&word") + "&_csrf=" + encode(token));
            assertEquals(302, login.statusCode());
            assertFalse(login.headers().firstValue("location").orElseThrow().contains("error"));
            var authorization = get(browser, authorize);
            assertEquals(302, authorization.statusCode());
            String location = authorization.headers().firstValue("location").orElseThrow();
            assertTrue(location.startsWith("https://client.example/callback?"), location);
            var codeMatch = Pattern.compile("[?&]code=([^&]+)").matcher(location);
            assertTrue(codeMatch.find(), location);
            String code = URLDecoder.decode(codeMatch.group(1), StandardCharsets.UTF_8);
            String grant = "grant_type=authorization_code&client_id=fixture-web&code=" + encode(code)
                    + "&redirect_uri=" + encode("https://client.example/callback");
            // Public token exchange must work without the browser session or a client secret.
            var publicClient = HttpClient.newHttpClient();
            var wrongProof = post(publicClient, base + "/oauth2/token", grant + "&code_verifier=" + "b".repeat(86));
            assertEquals(400, wrongProof.statusCode());
            var exchanged = post(publicClient, base + "/oauth2/token", grant + "&code_verifier=" + verifier);
            assertEquals(200, exchanged.statusCode(), exchanged.body());
            var json = new ObjectMapper().readTree(exchanged.body());
            assertFalse(json.path("access_token").asText().isBlank());
            assertEquals("Bearer", json.path("token_type").asText());
            var replay = post(publicClient, base + "/oauth2/token", grant + "&code_verifier=" + verifier);
            assertEquals(400, replay.statusCode());
            verify(dispatcher, atLeastOnce()).dispatch(any());
            verify(logs, never()).persistLog(any());
        } finally {
            server.stop();
            context.close();
        }
    }

    @Test
    void providerFailureMessageIsNotExpiryAndOverridesSavedAutomaticLogin() {
        LoginController controller = new LoginController();
        DefaultSavedRequest saved = mock(DefaultSavedRequest.class);
        lenient().when(saved.getParameterValues("loginType")).thenReturn(new String[]{"wxmp"});
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/index.html");
        request.getSession().setAttribute("SPRING_SECURITY_SAVED_REQUEST",
                saved);
        request.setParameter("thirdPartyLoginError", "provider");

        var view = controller.login(request);

        assertEquals("login/login", view.getViewName());
        assertEquals(true, view.getModel().get("thirdPartyLoginError"));
        assertEquals("第三方登录服务暂时不可用，请返回系统后重新发起登录。", view.getModel().get("error"));
    }

    @Test
    void missingAuthorizationContextStillRejectsThirdPartyLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/index.html");
        request.setQueryString("loginType=github");
        var view = new LoginController().login(request);
        assertEquals("login/login", view.getViewName());
        assertEquals(true, view.getModel().get("thirdPartyLoginError"));
        assertEquals("第三方登录请求无效或已失效，请返回系统后重新发起登录。", view.getModel().get("error"));
    }

    @Test
    void legacyAndUntrustedErrorsUseGenericMessageWithoutEchoingInput() {
        for (String reason : List.of("1", "<script>alert(1)</script>")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/index.html");
            request.setParameter("thirdPartyLoginError", reason);
            var view = new LoginController().login(request);
            assertEquals("第三方登录未完成，请返回系统后重试。", view.getModel().get("error"));
            assertEquals(true, view.getModel().get("thirdPartyLoginError"));
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(HttpClient client, String url, String form) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class FixtureSecurity {
        @Bean @Order(1)
        SecurityFilterChain authorization(HttpSecurity http) {
            http.oauth2AuthorizationServer(server -> http.securityMatcher(server.getEndpointsMatcher()))
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")));
            return http.build();
        }
        @Bean @Order(2)
        SecurityFilterChain login(HttpSecurity http) {
            http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults());
            return http.build();
        }
        @Bean UserDetailsService users() {
            return new InMemoryUserDetailsManager(User.withUsername("宋江")
                    .password("{noop}fixture+pass&word").roles("USER").build());
        }
        @Bean RegisteredClientRepository clients() {
            return new InMemoryRegisteredClientRepository(RegisteredClient.withId("fixture-row")
                    .clientId("fixture-web").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://client.example/callback").scope("profile")
                    .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                    .tokenSettings(TokenSettings.builder().accessTokenFormat(OAuth2TokenFormat.REFERENCE).build()).build());
        }
        @Bean AuthorizationServerSettings settings() {
            return AuthorizationServerSettings.builder().issuer("https://fixture.example").build();
        }
    }
}
