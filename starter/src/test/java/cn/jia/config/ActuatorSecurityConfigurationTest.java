package cn.jia.config;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.user.config.DefaultSecurityConfig;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActuatorSecurityConfigurationTest {
    @Test
    void dedicatedChainWinsForHealthWithoutOpeningManagementOrBusinessRoutes() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        new SpringContextHolder().setApplicationContext(context);
        context.register(SecurityWiring.class);
        try {
            context.refresh();
            FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
            SecurityFilterChain actuator = context.getBean(
                    "actuatorSecurityFilterChain", SecurityFilterChain.class);
            SecurityFilterChain fallback = context.getBean(
                    "defaultSecurityFilterChain", SecurityFilterChain.class);

            assertSame(actuator, firstMatching(proxy, request("", "/actuator/health")));
            assertSame(actuator, firstMatching(proxy, request("/api", "/api/actuator/health")));
            assertSame(fallback, firstMatching(proxy, request("", "/actuator/env")));
            assertSame(fallback, firstMatching(proxy, request("", "/business/ping")));

            RouteProbe routes = new RouteProbe();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(routes).addFilters(proxy).build();
            mvc.perform(get("/actuator/health")).andExpect(status().isNoContent());
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isNoContent());
            mvc.perform(get("/api/actuator/health").contextPath("/api"))
                    .andExpect(status().isNoContent());

            for (String path : new String[] {
                    "/actuator", "/actuator/env", "/actuator/healthcheck",
                    "/actuatorx/health", "/business/ping"
            }) {
                mvc.perform(get(path).contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isUnauthorized());
            }
            assertEquals(3, routes.hits.get());
        } finally {
            context.close();
            SpringContextHolder.cleanApplicationContext();
        }
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
    @Import({ActuatorSecurityConfiguration.class, DefaultSecurityConfig.class})
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
