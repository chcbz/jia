package cn.jia.user.config;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginPageSecurityRegressionTest {
    @Test
    void anonymousHtmlLoginPageMustReachItsControllerWithoutRedirectingToItself() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        new SpringContextHolder().setApplicationContext(context);
        context.register(DefaultSecurityConfig.class, SecurityWiring.class);
        try {
            context.refresh();
            FilterChainProxy security = context.getBean(FilterChainProxy.class);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoginRoute())
                    .addFilters(security)
                    .build();

            mvc.perform(get("/login/index.html")
                            .accept(MediaType.TEXT_HTML)
                            .header("Host", "api.chaoyoufan.cn")
                            .header("X-Forwarded-Host", "api.chaoyoufan.cn")
                            .header("X-Forwarded-Proto", "https")
                            .header("X-Forwarded-Port", "443"))
                    .andExpect(status().isNoContent());
        } finally {
            context.close();
            SpringContextHolder.cleanApplicationContext();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecurityWiring {
        @Bean UserService userService() { return mock(UserService.class); }
        @Bean PermsService permsService() { return mock(PermsService.class); }
        @Bean(name = "corsConfigurationSource")
        CorsConfigurationSource corsConfigurationSource() { return request -> null; }
    }

    @RestController
    static class LoginRoute {
        @GetMapping("/login/index.html")
        void login(HttpServletResponse response) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
        }
    }
}
