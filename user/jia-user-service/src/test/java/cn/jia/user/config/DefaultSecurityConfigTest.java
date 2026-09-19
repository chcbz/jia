package cn.jia.user.config;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.CustomUserDetails;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.security.AccountState;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DefaultSecurityConfigTest extends BaseMockTest {
    @Mock UserService userService;
    @Mock PermsService permsService;

    DefaultSecurityConfig config;
    UserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        config = new DefaultSecurityConfig();
        ReflectionTestUtils.setField(config, "userService", userService);
        ReflectionTestUtils.setField(config, "permsService", permsService);
        userDetailsService = config.userDetailsService();
    }

    @Test
    void actuatorFallbackMatcherOnlySelectsTheHealthTree() {
        assertFalse(DefaultSecurityConfig.selectsActuatorHealth(null));

        HttpServletRequest actuator = mock(HttpServletRequest.class);
        when(actuator.getContextPath()).thenReturn("");
        when(actuator.getRequestURI()).thenReturn("/actuator/health");
        assertTrue(DefaultSecurityConfig.selectsActuatorHealth(actuator));

        HttpServletRequest nestedContext = mock(HttpServletRequest.class);
        when(nestedContext.getContextPath()).thenReturn("/api");
        when(nestedContext.getRequestURI()).thenReturn("/api/actuator/health/readiness");
        assertTrue(DefaultSecurityConfig.selectsActuatorHealth(nestedContext));

        HttpServletRequest lookalike = mock(HttpServletRequest.class);
        when(lookalike.getContextPath()).thenReturn("");
        when(lookalike.getRequestURI()).thenReturn("/actuator/healthcheck");
        assertFalse(DefaultSecurityConfig.selectsActuatorHealth(lookalike));

        HttpServletRequest malformed = mock(HttpServletRequest.class);
        when(malformed.getContextPath()).thenReturn("/api");
        when(malformed.getRequestURI()).thenReturn("/actuator/health");
        assertFalse(DefaultSecurityConfig.selectsActuatorHealth(malformed));
    }

    @Test
    void fallbackChainPermitsOnlyHealthAndKeepsBusinessAuthenticated() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        new SpringContextHolder().setApplicationContext(context);
        context.register(DefaultSecurityConfig.class, SecurityWiring.class);
        try {
            context.refresh();
            FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
            SecurityFilterChain fallback = context.getBean("defaultSecurityFilterChain", SecurityFilterChain.class);
            assertSame(fallback, firstMatching(proxy, request("", "/actuator/health")));
            assertSame(fallback, firstMatching(proxy, request("/api", "/api/actuator/health")));

            RouteProbe routes = new RouteProbe();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(routes).addFilters(proxy).build();
            mvc.perform(get("/actuator/health")).andExpect(status().isNoContent());
            mvc.perform(get("/api/actuator/health").contextPath("/api"))
                    .andExpect(status().isNoContent());

            for (String path : new String[] {
                    "/actuator", "/actuator/env", "/actuator/healthcheck",
                    "/actuatorx/health", "/business/ping"
            }) {
                mvc.perform(get(path).contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isUnauthorized());
            }
            assertEquals(2, routes.hits.get());
        } finally {
            context.close();
            SpringContextHolder.cleanApplicationContext();
        }
    }

    @Test
    void freezesStableIdentityAndEpochForActiveUser() {
        UserEntity user = activeUser(17L, "alice", "Jia-A", 9L);
        when(userService.findByUsername("alice")).thenReturn(user);
        when(permsService.findByUserId(17L)).thenReturn(List.of());

        CustomUserDetails details = (CustomUserDetails) userDetailsService.loadUserByUsername("alice");
        assertEquals(17, details.getUserId());
        assertEquals("Jia-A", details.getJiacn());
        assertEquals(9, details.getLoginAuthEpoch());
        assertEquals("alice", details.getUsername());
    }

    @Test
    void missingInactiveOrMismatchedIdentityUsesSameAuthenticationFailure() {
        when(userService.findByUsername("missing")).thenReturn(null);
        assertGenericFailure("missing");

        UserEntity suspended = activeUser(18L, "suspended", "Jia-B", 2L)
                .setAccountState(AccountState.SUSPENDED.name());
        when(userService.findByUsername("suspended")).thenReturn(suspended);
        assertGenericFailure("suspended");

        UserEntity invalidEpoch = activeUser(19L, "invalid-epoch", "Jia-C", -1L);
        when(userService.findByUsername("invalid-epoch")).thenReturn(invalidEpoch);
        assertGenericFailure("invalid-epoch");
        verify(permsService, never()).findByUserId(anyLong());
    }

    private static UserEntity activeUser(long id, String username, String jiacn, long authEpoch) {
        return new UserEntity()
                .setId(id)
                .setUsername(username)
                .setPassword("encoded")
                .setJiacn(jiacn)
                .setAccountState(AccountState.ACTIVE.name())
                .setAuthEpoch(authEpoch);
    }

    private void assertGenericFailure(String username) {
        UsernameNotFoundException failure = assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername(username));
        assertEquals("Authentication failed", failure.getMessage());
    }

    private static SecurityFilterChain firstMatching(
            FilterChainProxy proxy, MockHttpServletRequest request) {
        return proxy.getFilterChains().stream()
                .filter(chain -> chain.matches(request))
                .findFirst()
                .orElseThrow();
    }

    private static MockHttpServletRequest request(String contextPath, String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        request.setContextPath(contextPath);
        return request;
    }

    @Configuration(proxyBeanMethods = false)
    static class SecurityWiring {
        @Bean
        UserService contextUserService() {
            return mock(UserService.class);
        }

        @Bean
        PermsService contextPermsService() {
            return mock(PermsService.class);
        }

        @Bean(name = "corsConfigurationSource")
        CorsConfigurationSource corsConfigurationSource() {
            return request -> null;
        }
    }

    @RestController
    static class RouteProbe {
        private final AtomicInteger hits = new AtomicInteger();

        @RequestMapping({
                "/actuator", "/actuator/health", "/actuator/health/readiness",
                "/actuator/env", "/actuator/healthcheck", "/actuatorx/health", "/business/ping"
        })
        void route(HttpServletResponse response) {
            hits.incrementAndGet();
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
}
