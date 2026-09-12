package cn.jia.oauth.config;

import cn.jia.user.security.AccountSecurityService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResourceServerAsyncSecurityTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private SseProbeController controller;
    private AsyncAuthenticationProbeFilter asyncAuthenticationProbe;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context, "oauth.resource.uris[0]=/chat/**");
        context.register(TestApplication.class);
        context.refresh();

        controller = context.getBean(SseProbeController.class);
        asyncAuthenticationProbe = new AsyncAuthenticationProbeFilter();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class), asyncAuthenticationProbe)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void validJwtIdentitySurvivesCommittedSseAsyncCompletionWithoutSession() throws Exception {
        MvcResult initial = mockMvc.perform(get("/chat/security-stream")
                        .header("Authorization", "Bearer valid-user"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertEquals("user-17", controller.requestAuthentication.get().getName());
        assertNull(initial.getRequest().getSession(false));

        controller.sendIdentityAndComplete();
        assertTrue(initial.getResponse().isCommitted(), "the SSE frame must commit before ASYNC redispatch");

        MvcResult completed = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andReturn();

        JwtAuthenticationToken asyncAuthentication = assertInstanceOf(
                JwtAuthenticationToken.class, asyncAuthenticationProbe.asyncAuthentication.get());
        assertEquals("user-17", asyncAuthentication.getName());
        assertTrue(asyncAuthentication.isAuthenticated());
        assertTrue(completed.getResponse().getContentAsString().contains("user-17"));
        assertNull(completed.getRequest().getSession(false));
        assertEquals(1, controller.invocations.get());
    }

    @Test
    void freshRequestsRejectMissingInvalidAndSessionAuthenticationWithoutCreatingSession()
            throws Exception {
        MvcResult missing = mockMvc.perform(get("/chat/security-stream"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertNull(missing.getRequest().getSession(false));

        MvcResult invalid = mockMvc.perform(get("/chat/security-stream")
                        .header("Authorization", "Bearer invalid-user"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertNull(invalid.getRequest().getSession(false));

        SecurityContext sessionContext = SecurityContextHolder.createEmptyContext();
        sessionContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                "session-user", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                sessionContext);

        MvcResult sessionOnly = mockMvc.perform(get("/chat/security-stream")
                        .session(session)
                        .cookie(new Cookie("JSESSIONID", session.getId())))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertSame(session, sessionOnly.getRequest().getSession(false));
        assertNull(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST"));
        assertEquals(0, controller.invocations.get());
    }

    @Test
    void asyncRedispatchWithoutRequestAuthenticationRemainsDenied() throws Exception {
        MvcResult unauthorized = mockMvc.perform(get("/chat/security-stream")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ASYNC);
                            return request;
                        }))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertNull(unauthorized.getRequest().getSession(false));
        assertNull(asyncAuthenticationProbe.asyncAuthentication.get());
        assertEquals(0, controller.invocations.get());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableConfigurationProperties
    @Import(ResourceServerConfig.class)
    static class TestApplication {
        @Bean
        SseProbeController sseProbeController() {
            return new SseProbeController();
        }

        @Bean
        AccountSecurityService accountSecurityService() {
            return org.mockito.Mockito.mock(AccountSecurityService.class);
        }

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                if (!"valid-user".equals(token)) {
                    throw new BadJwtException("Invalid token");
                }
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("user-17")
                        .claim("client_id", "public-web")
                        .issuedAt(Instant.now().minusSeconds(5))
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build();
            };
        }
    }

    @RestController
    static class SseProbeController {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<Authentication> requestAuthentication = new AtomicReference<>();
        private final AtomicReference<SseEmitter> emitter = new AtomicReference<>();

        @GetMapping(value = "/chat/security-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream(Authentication authentication) {
            invocations.incrementAndGet();
            requestAuthentication.set(authentication);
            SseEmitter current = new SseEmitter(30_000L);
            emitter.set(current);
            return current;
        }

        void sendIdentityAndComplete() throws IOException {
            SseEmitter current = emitter.get();
            current.send(SseEmitter.event()
                    .name("identity")
                    .data(requestAuthentication.get().getName()));
            current.complete();
        }
    }

    static class AsyncAuthenticationProbeFilter implements Filter {
        private final AtomicReference<Authentication> asyncAuthentication = new AtomicReference<>();

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            if (request.getDispatcherType() == DispatcherType.ASYNC) {
                asyncAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
            }
            chain.doFilter(request, response);
        }
    }
}
