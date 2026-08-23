package cn.jia.oauth.api;

import cn.jia.oauth.config.ResourceServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationResourceSecurityTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestApplication.class);
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void rejectsUnauthenticatedAndFormLoginSessionOnlyRequests() throws Exception {
        mockMvc.perform(get("/resource"))
                .andExpect(status().isUnauthorized());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                formLoginSecurityContext());
        mockMvc.perform(get("/resource").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidUserJwtAndDoesNotOverDiscloseClaims() throws Exception {
        mockMvc.perform(get("/resource").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("user-17"))
                .andExpect(jsonPath("$.clientId").value("public-web"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.jiacn").value("jia-17"))
                .andExpect(jsonPath("$.scopes", contains("openid", "profile", "write")))
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.arbitrary_claim").doesNotExist());
    }

    @Test
    void acceptsValidMachineJwtWithoutUserClaims() throws Exception {
        mockMvc.perform(get("/resource").header("Authorization", "Bearer machine-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("machine-client"))
                .andExpect(jsonPath("$.clientId").value("machine-client"))
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.jiacn").doesNotExist())
                .andExpect(jsonPath("$.scopes", hasSize(0)))
                .andExpect(jsonPath("$.*", hasSize(3)));
    }

    @Test
    void preservesExactIdentityClaimsIncludingSurroundingWhitespace() throws Exception {
        mockMvc.perform(get("/resource").header("Authorization", "Bearer exact-identity-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(" user-17 "))
                .andExpect(jsonPath("$.clientId").value(" public-web "))
                .andExpect(jsonPath("$.username").value(" alice "))
                .andExpect(jsonPath("$.jiacn").value(" jia-17 "))
                .andExpect(jsonPath("$.scopes", hasSize(0)));
    }

    @Test
    void rejectsMalformedJwtAndBlankOrMalformedIdentityClaims() throws Exception {
        mockMvc.perform(get("/resource").header("Authorization", "Bearer malformed-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/resource").header("Authorization", "Bearer missing-claims-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/resource").header("Authorization", "Bearer blank-required-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/resource").header("Authorization", "Bearer blank-optional-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/resource").header("Authorization", "Bearer malformed-optional-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/resource").header("Authorization", "Bearer malformed-scope-token"))
                .andExpect(status().isUnauthorized());
    }

    private static SecurityContext formLoginSecurityContext() {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                "session-user", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import(ResourceServerConfig.class)
    static class TestApplication {
        @Bean
        AuthenticationController authenticationController() {
            return new AuthenticationController();
        }

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> switch (token) {
                case "user-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "username", "alice",
                        "jiacn", "jia-17",
                        "scope", List.of("write", "openid", "profile"),
                        "access_token", "must-not-leak",
                        "arbitrary_claim", "must-not-leak"));
                case "machine-token" -> jwt(token, Map.of(
                        "sub", "machine-client",
                        "client_id", "machine-client"));
                case "exact-identity-token" -> jwt(token, Map.of(
                        "sub", " user-17 ",
                        "client_id", " public-web ",
                        "username", " alice ",
                        "jiacn", " jia-17 "));
                case "missing-claims-token" -> jwt(token, Map.of("scope", "openid"));
                case "blank-required-token" -> jwt(token, Map.of(
                        "sub", " ",
                        "client_id", "public-web"));
                case "blank-optional-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "username", "\t"));
                case "malformed-optional-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "jiacn", List.of("jia-17")));
                case "malformed-scope-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "scope", List.of("openid", " ")));
                default -> throw new BadJwtException("Malformed token");
            };
        }

        private static Jwt jwt(String tokenValue, Map<String, Object> claims) {
            Jwt.Builder builder = Jwt.withTokenValue(tokenValue)
                    .header("alg", "RS256")
                    .issuedAt(Instant.now().minusSeconds(60))
                    .expiresAt(Instant.now().plusSeconds(300));
            claims.forEach(builder::claim);
            return builder.build();
        }
    }
}
