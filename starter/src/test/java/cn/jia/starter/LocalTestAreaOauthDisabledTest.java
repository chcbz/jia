package cn.jia.starter;

import cn.jia.oauth.config.AuthorizationServerConfig;
import cn.jia.core.datasource.DruidSource;
import cn.jia.oauth.security.TenantAuthorizationRequestProvider;
import cn.jia.oauth.security.TenantConsentGrantProvider;
import cn.jia.oauth.security.TenantTokenGrantProvider;
import cn.jia.oauth.security.TenantAuthorizationService;
import cn.jia.oauth.security.JdbcTenantAuthorizationRepository;
import cn.jia.oauth.service.ClientService;
import cn.jia.user.security.AccountSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter;
import org.springframework.security.oauth2.server.authorization.web.OAuth2TokenEndpointFilter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Isolated production OAuth configuration with the actual local-area profile properties. */
class LocalTestAreaOauthDisabledTest {
    @Test
    void localProfileUsesTheActualDruidDbUrlBinding() throws Exception {
        String source = new ClassPathResource("application-local-test-area.properties")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(source.contains("spring.datasource.db-url="));
        DruidSource bound = Binder.get(new MockEnvironment()
                        .withProperty("spring.datasource.db-url", "jdbc:mysql://localhost/cyf"))
                .bind("spring.datasource", Bindable.of(DruidSource.class))
                .orElseThrow(() -> new IllegalStateException(
                        "spring.datasource must bind to DruidSource"));
        assertEquals("jdbc:mysql://localhost/cyf", bound.getDbUrl());
    }

    @Test
    void explicitDisabledOverrideDoesNotRegisterAuthorizationOrJwksEndpoints() throws Exception {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(
                new ResourcePropertySource("classpath:application-local-test-area.properties"));
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "cyf.oauth.authorization-server.enabled=false");
        context.register(AuthorizationServerConfig.class, Fixture.class);
        try {
            context.refresh();
            assertEquals("LOCAL_TEST", context.getEnvironment().getProperty("cyf.deployment.stage"));
            assertEquals("1", context.getEnvironment().getProperty("cyf.tenant.fixed-tenant-id"));
            assertEquals("conversation", context.getEnvironment().getProperty(
                    "cyf.deployment.allowed-modules[0]"));
            assertFalse(context.getEnvironment().getProperty(
                    "cyf.deployment.allow-no-business-modules", Boolean.class, true));
            assertFalse(context.getEnvironment().getProperty(
                    "cyf.oauth.authorization-server.enabled", Boolean.class, true));
            assertFalse(context.getEnvironment().getProperty(
                    "chat.agent.tools.enabled", Boolean.class, true));
            assertFalse(context.getEnvironment().getProperty(
                    "jia.chat.service.websocket.enable", Boolean.class, true));
            assertEquals(0, context.getBeansOfType(SecurityFilterChain.class).size());
            assertFalse(context.getEnvironment().getProperty(
                    "chat.memory.aggregate.enabled", Boolean.class, true));
            assertFalse(context.getEnvironment().getProperty(
                    "chat.memory.retention-cleanup.enabled", Boolean.class, true));
            assertEquals(0, context.getBeansOfType(OAuth2AuthorizationService.class).size());
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/oauth2/authorize")).andExpect(status().isNotFound());
            mvc.perform(post("/oauth2/token")).andExpect(status().isNotFound());
            mvc.perform(get("/oauth2/jwks")).andExpect(status().isNotFound());
            mvc.perform(get("/oauth2/tenant-options")).andExpect(status().isNotFound());
        } finally {
            context.close();
        }
    }

    @Test
    void enabledCentralChainUsesTenantGuards() {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "cyf.oauth.authorization-server.enabled=true");
            context.register(AuthorizationServerConfig.class, Fixture.class, SecurityFixture.class);
            context.refresh();
            assertTrue(context.getBean(OAuth2AuthorizationService.class) instanceof TenantAuthorizationService);
            assertEquals(0, context.getBean("positiveJdbcOperations", JdbcTemplate.class)
                    .queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                            + "WHERE LOWER(TABLE_NAME) = 'oauth2_authorization'", Integer.class));
            var security = context.getBean(FilterChainProxy.class);
            var selectionRequest = new MockHttpServletRequest("POST", "/oauth2/tenant-selection");
            var selection = security.getFilterChains().stream().filter(candidate -> candidate.matches(selectionRequest))
                    .findFirst().orElseThrow();
            assertEquals(1L, selection.getFilters().stream().filter(CsrfFilter.class::isInstance).count());
            assertEquals(0L, selection.getFilters().stream().filter(OAuth2AuthorizationEndpointFilter.class::isInstance).count());
            var authorizeRequest = new MockHttpServletRequest("GET", "/oauth2/authorize");
            var chain = security.getFilterChains().stream().filter(candidate -> candidate.matches(authorizeRequest))
                    .findFirst().orElseThrow();
            var authorization = chain.getFilters().stream().filter(OAuth2AuthorizationEndpointFilter.class::isInstance)
                    .findFirst().orElseThrow();
            var consentProviders = ((ProviderManager) ReflectionTestUtils.getField(
                    authorization, "authenticationManager")).getProviders();
            assertEquals(1L, consentProviders.stream().filter(TenantAuthorizationRequestProvider.class::isInstance).count());
            assertEquals(1L, consentProviders.stream().filter(TenantConsentGrantProvider.class::isInstance).count());
            assertEquals(0L, consentProviders.stream().filter(OAuth2AuthorizationCodeRequestAuthenticationProvider.class::isInstance).count());
            assertEquals(0L, consentProviders.stream().filter(OAuth2AuthorizationConsentAuthenticationProvider.class::isInstance).count());
            var token = chain.getFilters().stream().filter(OAuth2TokenEndpointFilter.class::isInstance)
                    .findFirst().orElseThrow();
            var tokenProviders = ((ProviderManager) ReflectionTestUtils.getField(
                    token, "authenticationManager")).getProviders();
            assertEquals(2L, tokenProviders.stream().filter(TenantTokenGrantProvider.class::isInstance).count());
            assertEquals(0L, tokenProviders.stream().filter(OAuth2AuthorizationCodeAuthenticationProvider.class::isInstance).count());
            assertEquals(0L, tokenProviders.stream().filter(OAuth2RefreshTokenAuthenticationProvider.class::isInstance).count());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Fixture {
        @Bean
        ClientService clientService() {
            return mock(ClientService.class);
        }

        @Bean
        AccountSecurityService accountSecurityService() {
            return mock(AccountSecurityService.class);
        }

        @Bean
        JdbcTenantAuthorizationRepository tenantAuthorizationRepository() {
            return mock(JdbcTenantAuthorizationRepository.class);
        }

        @Bean
        JdbcOperations jdbcOperations() {
            return new JdbcTemplate(mock(DataSource.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class SecurityFixture {
        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        @Primary
        JdbcOperations positiveJdbcOperations() throws Exception {
            var dataSource = new DriverManagerDataSource("jdbc:h2:mem:central_" + java.util.UUID.randomUUID()
                    + ";MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
            return new JdbcTemplate(dataSource);
        }
    }
}
