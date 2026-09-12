package cn.jia.user.config;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DefaultSecurityWebSocketPermitTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context, "user.permit.ignore-uris[0]=/ws/**");
        new SpringContextHolder().setApplicationContext(context);
        context.register(TestApplication.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
        SpringContextHolder.cleanApplicationContext();
    }

    @Test
    void configuredWebSocketPathReachesItsHandshakeBoundary() throws Exception {
        mvc.perform(get("/ws/agent/channel"))
                .andExpect(status().isOk())
                .andExpect(content().string("handshake"));

        mvc.perform(get("/private"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void errorDispatchReachesTheExistingErrorBoundaryWithoutBecomingAuthenticationFailure()
            throws Exception {
        mvc.perform(get("/error"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(content().string("error-boundary"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableConfigurationProperties
    @Import(DefaultSecurityConfig.class)
    static class TestApplication {
        @Bean
        SpringContextHolder springContextHolder() {
            return new SpringContextHolder();
        }

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        PermsService permsService() {
            return mock(PermsService.class);
        }

        @Bean
        AccountSecurityService accountSecurityService() {
            return mock(AccountSecurityService.class);
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/ws/agent/channel")
        String handshake() {
            return "handshake";
        }

        @GetMapping("/private")
        String privateRoute() {
            return "private";
        }

        @GetMapping("/error")
        String errorBoundary() {
            return "error-boundary";
        }
    }
}
