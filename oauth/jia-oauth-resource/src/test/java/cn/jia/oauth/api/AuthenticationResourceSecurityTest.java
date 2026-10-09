package cn.jia.oauth.api;

import cn.jia.core.config.ExceptionHandlerAdvice;
import cn.jia.oauth.config.ResourceServerConfig;
import cn.jia.oauth.security.AccountSecurityJwtValidator;
import cn.jia.oauth.security.TenantAuthorizationAttributes;
import cn.jia.oauth.security.TenantAuthorizationVerifier;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
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
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationResourceSecurityTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                context, "oauth.resource.uris[0]=/agent/**");
        context.register(TestApplication.class);
        context.refresh();

        FilterChainProxy security = context.getBean(FilterChainProxy.class);
        MockHttpServletRequest agentRequest = new MockHttpServletRequest("GET", "/agent/probe");
        long matchingChains = security.getFilterChains().stream()
                .filter(chain -> chain.matches(agentRequest))
                .count();
        assertEquals(1L, matchingChains, "configured agent route must use the resource chain");

        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(security)
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
    void returnsTopLevelIdentityForValidUserJwtWithoutOverDisclosure() throws Exception {
        mockMvc.perform(get("/resource").header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("user-17"))
                .andExpect(jsonPath("$.clientId").value("public-web"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.jiacn").value("jia-17"))
                .andExpect(jsonPath("$.scopes", contains("openid", "profile", "write")))
                .andExpect(jsonPath("$.tenantId").value("1"))
                .andExpect(jsonPath("$.*", hasSize(13)))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.arbitrary_claim").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("must-not-leak"))));
    }

    @Test
    void previouslyIssuedUnboundUserTokenCannotAccessIdentityOrBusinessRoutes() throws Exception {
        mockMvc.perform(get("/resource").header("Authorization", "Bearer unbound-user-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/agent/probe").header("Authorization", "Bearer unbound-user-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTopLevelIdentityForValidMachineJwtWithAbsentScope() throws Exception {
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
    void configuredAgentResourceUsesTheSameAccountGateWithoutSessionOrRequestCache()
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/agent/probe")
                        .session(session)
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
        assertNull(session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));

        mockMvc.perform(get("/agent/probe")
                        .header("Authorization", "Bearer stale-user-token"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/agent/probe").session(session))
                .andExpect(status().isUnauthorized());
        assertNull(session.getAttribute("SPRING_SECURITY_SAVED_REQUEST"));
    }

    @Test
    void strictDeploymentBlocksLegacyAgentRouteAfterBearerButKeepsIdentityAvailable()
            throws Exception {
        try (AnnotationConfigWebApplicationContext strict = new AnnotationConfigWebApplicationContext()) {
            strict.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(strict,
                    "cyf.tenant.require-bound-identity=true",
                    "cyf.tenant.allowed-tenants[0]=0",
                    "cyf.tenant.allowed-tenants[1]=1",
                    "oauth.resource.uris[0]=/agent/**",
                    "oauth.resource.uris[1]=/phrase/**",
                    "oauth.resource.uris[2]=/media/**",
                    "oauth.resource.uris[3]=/dwz/**",
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example.test/jwks");
            strict.register(TestApplication.class);
            strict.refresh();
            // Both migrated-unknown and legacy URIs use this same bearer chain.
            for (String path : List.of("/agent/probe", "/chat/probe", "/archive/probe",
                    "/juyiting/probe", "/task/probe", "/job/probe", "/economy/probe",
                    "/point/probe", "/workflow/probe", "/tip/probe", "/user/point/change",
                    "/ws/chat", "/phrase/probe", "/media/probe")) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
                assertEquals(1L, strict.getBean(FilterChainProxy.class).getFilterChains().stream()
                        .filter(chain -> chain.matches(request)).count(), path);
            }
            for (MockHttpServletRequest request : List.of(
                    new MockHttpServletRequest("GET", "/user/my"),
                    new MockHttpServletRequest("POST", "/user/me/sessions/revoke-all"))) {
                assertEquals(1L, strict.getBean(FilterChainProxy.class).getFilterChains().stream()
                        .filter(chain -> chain.matches(request)).count(), request.getRequestURI());
            }
            // /user is also the legacy OAuth Principal endpoint; it must not
            // accidentally enter the tenant-zero /user/** business gate.
            assertEquals(0L, strict.getBean(FilterChainProxy.class).getFilterChains().stream()
                    .filter(chain -> chain.matches(new MockHttpServletRequest("GET", "/user"))).count());
            assertEquals(0L, strict.getBean(FilterChainProxy.class).getFilterChains().stream()
                    .filter(chain -> chain.matches(new MockHttpServletRequest("HEAD", "/user"))).count());
            MockMvc guarded = MockMvcBuilders.webAppContextSetup(strict)
                    .addFilters(strict.getBean(FilterChainProxy.class)).build();
            for (String path : List.of("/agent/probe", "/chat/probe", "/archive/probe", "/juyiting/probe",
                    "/task/probe", "/job/probe", "/economy/probe", "/point/probe",
                    "/workflow/probe", "/tip/probe", "/user/point/change",
                    "/phrase/probe", "/media/probe")) {
                guarded.perform(get(path).header("Authorization", "Bearer user-token"))
                        .andExpect(status().isForbidden())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                                .string("Cache-Control", "private, no-store"));
                guarded.perform(get(path)).andExpect(status().isUnauthorized());
            }
            guarded.perform(get("/ws/chat").header("Authorization", "Bearer user-token"))
                    .andExpect(status().isForbidden())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Cache-Control", "private, no-store"));
            guarded.perform(get("/ws/chat")).andExpect(status().isUnauthorized());
            guarded.perform(get("/user/my").header("Authorization", "Bearer user-token"))
                    .andExpect(status().isOk());
            guarded.perform(post("/user/me/sessions/revoke-all")
                            .header("Authorization", "Bearer user-token"))
                    .andExpect(status().isOk());
            guarded.perform(get("/user/my")).andExpect(status().isUnauthorized());
            guarded.perform(post("/user/me/sessions/revoke-all"))
                    .andExpect(status().isUnauthorized());
            guarded.perform(get("/resource")
                            .header("Authorization", "Bearer user-token"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void malformedSignedIdentityClaimsRemainEmptyBody401WithProductionAdviceRegistered()
            throws Exception {
        for (String token : List.of(
                "missing-claims-token",
                "blank-required-token",
                "blank-optional-token",
                "malformed-optional-token",
                "blank-scope-token",
                "malformed-scope-token",
                "surrounding-scope-token",
                "embedded-scope-token",
                "unicode-scope-token",
                "nel-scope-token")) {
            mockMvc.perform(get("/resource").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
        }
    }

    @Test
    void malformedJwtIsRejectedByBearerAuthentication() throws Exception {
        mockMvc.perform(get("/resource").header("Authorization", "Bearer malformed-token"))
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
    @EnableConfigurationProperties
    @Import({ResourceServerConfig.class, ExceptionHandlerAdvice.class})
    static class TestApplication {
        @Bean
        OAuthResourceIdentityController authenticationController() {
            return new OAuthResourceIdentityController();
        }

        @Bean
        AgentProbeController agentProbeController() {
            return new AgentProbeController();
        }

        @Bean
        AccountSecurityService accountSecurityService() {
            AccountSecurityService service = mock(AccountSecurityService.class);
            when(service.findByUserId(17)).thenReturn(Optional.of(
                    new AccountSecuritySnapshot(17, "jia-17", AccountState.ACTIVE, 4)));
            when(service.findByUserId(18)).thenReturn(Optional.of(
                    new AccountSecuritySnapshot(18, " jia-17 ", AccountState.ACTIVE, 2)));
            return service;
        }

        @Bean
        TenantAuthorizationVerifier tenantAuthorizationVerifier() {
            return (selected, userId, clientId) -> selected.equals(binding())
                    && ((userId == 17 && "public-web".equals(clientId))
                    || (userId == 18 && " public-web ".equals(clientId)));
        }

        private static TenantAuthorizationAttributes binding() {
            return new TenantAuthorizationAttributes(cn.jia.core.tenant.TenantId.of("1"),
                    cn.jia.core.tenant.DataDomainId.of("cyf"), "华南测试区",
                    TenantAuthorizationAttributes.TenantKind.TEST, "2", "3", "4");
        }

        @Bean
        @Primary
        JwtDecoder testJwtDecoder(AccountSecurityService accountSecurityService, TenantAuthorizationVerifier verifier) {
            return token -> {
                Jwt jwt = switch (token) {
                case "user-token" -> jwt(token, Map.of(
                        "token_kind", "user",
                        "uid", "17",
                        "auth_epoch", 4L,
                        "sub", "user-17",
                        "client_id", "public-web",
                        "username", "alice",
                        "jiacn", "jia-17",
                        "scope", List.of("write", "openid", "profile"),
                        "access_token", "must-not-leak",
                        "arbitrary_claim", "must-not-leak"));
                case "stale-user-token" -> jwt(token, Map.of(
                        "token_kind", "user",
                        "uid", "17",
                        "auth_epoch", 3L,
                        "sub", "user-17",
                        "client_id", "public-web",
                        "username", "alice",
                        "jiacn", "jia-17"));
                case "unbound-user-token" -> jwt(token, Map.of(
                        "token_kind", "user", "uid", "17", "jiacn", "jia-17", "username", "alice",
                        "auth_epoch", 4L, "sub", "user-17", "client_id", "public-web"));
                case "machine-token" -> jwt(token, Map.of(
                        "token_kind", "machine",
                        "sub", "machine-client",
                        "client_id", "machine-client"));
                case "exact-identity-token" -> jwt(token, Map.of(
                        "token_kind", "user",
                        "uid", "18",
                        "auth_epoch", 2L,
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
                case "blank-scope-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "scope", " "));
                case "malformed-scope-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "scope", List.of("openid", " ")));
                case "surrounding-scope-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "scope", List.of("openid", " read ")));
                case "embedded-scope-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "scope", List.of("openid", "read\twrite")));
                case "unicode-scope-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "scope", List.of("openid", "read\u2003write")));
                case "nel-scope-token" -> jwt(token, Map.of(
                        "sub", "user-17",
                        "client_id", "public-web",
                        "scope", List.of("openid", "read\u0085write")));
                default -> throw new BadJwtException("Malformed token");
                };
                if (new AccountSecurityJwtValidator(accountSecurityService, verifier).validate(jwt).hasErrors()) {
                    throw new BadJwtException("Invalid token");
                }
                return jwt;
            };
        }

        private static Jwt jwt(String tokenValue, Map<String, Object> claims) {
            Jwt.Builder builder = Jwt.withTokenValue(tokenValue)
                    .header("alg", "RS256")
                    .issuedAt(Instant.now().minusSeconds(60))
                    .expiresAt(Instant.now().plusSeconds(300));
            claims.forEach(builder::claim);
            if ("user-token".equals(tokenValue) || "exact-identity-token".equals(tokenValue)) {
                builder.claim("tenant_id", "1").claim("data_domain_id", "cyf")
                        .claim("tenant_name", "华南测试区").claim("tenant_kind", "TEST")
                        .claim("tenant_session_epoch", "2").claim("membership_revision", "3")
                        .claim("client_tenant_grant_revision", "4").claim("iss", "https://issuer.example");
            }
            return builder.build();
        }

    }

    @RestController
    static class AgentProbeController {

        @GetMapping("/phrase/probe")
        String phraseProbe() {
            return "ok";
        }

        @GetMapping("/media/probe")
        String mediaProbe() {
            return "ok";
        }
        @GetMapping("/agent/probe")
        String probe() {
            return "ok";
        }

        @GetMapping("/chat/probe")
        String chatProbe() {
            return "ok";
        }

        @GetMapping("/archive/probe")
        String archiveProbe() {
            return "ok";
        }

        @GetMapping("/juyiting/probe")
        String hallProbe() {
            return "ok";
        }

        @GetMapping("/task/probe")
        String taskProbe() {
            return "ok";
        }

        @GetMapping("/job/probe")
        String jobProbe() {
            return "ok";
        }

        @GetMapping("/economy/probe")
        String economyProbe() {
            return "ok";
        }

        @GetMapping("/point/probe")
        String pointProbe() {
            return "ok";
        }

        @GetMapping("/user/my")
        String myProfile() {
            return "ok";
        }

        @PostMapping("/user/me/sessions/revoke-all")
        String revokeSessions() {
            return "ok";
        }
    }
}
