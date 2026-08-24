package cn.jia.oauth.api;

import cn.jia.oauth.config.ResourceServerConfig;
import cn.jia.oauth.security.AccountSecurityJwtValidator;
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
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionRevocationResourceSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;
    private InMemoryAccountSecurityService accounts;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "oauth.resource.uris[0]=/user/**");
        context.register(TestApplication.class);
        context.refresh();
        accounts = context.getBean(InMemoryAccountSecurityService.class);
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
    void successfulRevokeCommitsThenSameTokenIs401AcrossProtectedUris() throws Exception {
        mockMvc.perform(post("/user/me/sessions/revoke-all").header("Authorization", "Bearer user-token"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/user/me/sessions/revoke-all").header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/user/my").header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/user/protected").header("Authorization", "Bearer user-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerUserOnlyAclRejectsSessionCookieApiKeyMachineAnonymousAndWrongMethodsWithoutWrite() throws Exception {
        MockHttpSession session = new MockHttpSession();
        SecurityContext sessionContext = SecurityContextHolder.createEmptyContext();
        sessionContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                "session", "n/a", AuthorityUtils.createAuthorityList("ROLE_USER")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sessionContext);

        mockMvc.perform(post("/user/me/sessions/revoke-all").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/user/me/sessions/revoke-all").cookie(new jakarta.servlet.http.Cookie("CTX", "ignored")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/user/me/sessions/revoke-all").header("X-API-Key", "key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/user/me/sessions/revoke-all").header("Authorization", "Bearer machine-token"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/user/me/sessions/revoke-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/user/me/sessions/revoke-all").header("Authorization", "Bearer user-token"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/user/me/sessions/revoke-all").header("Authorization", "Bearer user-token"))
                .andExpect(status().isMethodNotAllowed());
        org.junit.jupiter.api.Assertions.assertEquals(4, accounts.epoch.get());
    }

    @Test
    void zeroRowCasIs409WithStableCodeAndNoRetry() throws Exception {
        accounts.forceCasConflict = true;
        mockMvc.perform(post("/user/me/sessions/revoke-all").header("Authorization", "Bearer user-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_EPOCH_CONFLICT"));
        org.junit.jupiter.api.Assertions.assertEquals(1, accounts.casAttempts);
        org.junit.jupiter.api.Assertions.assertEquals(4, accounts.epoch.get());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableConfigurationProperties
    @Import(ResourceServerConfig.class)
    static class TestApplication {
        @Bean InMemoryAccountSecurityService accountSecurityService() { return new InMemoryAccountSecurityService(); }
        @Bean SessionRevocationController sessionRevocationController(AccountSecurityService service) {
            return new SessionRevocationController(service);
        }
        @Bean ProtectedUserController protectedUserController() { return new ProtectedUserController(); }

        @Bean
        @Primary
        JwtDecoder testJwtDecoder(AccountSecurityService service) {
            return token -> {
                Jwt jwt = switch (token) {
                    case "user-token" -> jwt(token, Map.of(
                            "token_kind", "user", "uid", "17", "jiacn", "Jia-A", "username", "alice",
                            "auth_epoch", 4L, "sub", "alice", "client_id", "web"));
                    case "machine-token" -> jwt(token, Map.of(
                            "token_kind", "machine", "sub", "agent", "client_id", "agent"));
                    default -> throw new BadJwtException("Malformed token");
                };
                if (new AccountSecurityJwtValidator(service).validate(jwt).hasErrors()) {
                    throw new BadJwtException("Invalid token");
                }
                return jwt;
            };
        }

        private static Jwt jwt(String value, Map<String, Object> claims) {
            Jwt.Builder builder = Jwt.withTokenValue(value).header("alg", "RS256")
                    .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
            claims.forEach(builder::claim);
            return builder.build();
        }
    }

    @RestController
    static class ProtectedUserController {
        @GetMapping({"/user/my", "/user/protected"})
        ResponseEntity<Void> protectedEndpoint() { return ResponseEntity.noContent().build(); }
    }

    static final class InMemoryAccountSecurityService implements AccountSecurityService {
        private final AtomicLong epoch = new AtomicLong(4);
        private volatile boolean forceCasConflict;
        private volatile int casAttempts;

        @Override
        public Optional<AccountSecuritySnapshot> findByUserId(long userId) {
            return userId == 17
                    ? Optional.of(new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, epoch.get()))
                    : Optional.empty();
        }

        @Override
        public Optional<AccountSecuritySnapshot> findUniqueByExactJiacn(String jiacn) {
            return "Jia-A".equals(jiacn) ? findByUserId(17) : Optional.empty();
        }

        @Override
        public int revokeAllSessions(long userId, long expectedEpoch) {
            casAttempts++;
            if (forceCasConflict || userId != 17 || expectedEpoch == Long.MAX_VALUE) return 0;
            return epoch.compareAndSet(expectedEpoch, expectedEpoch + 1) ? 1 : 0;
        }
    }
}
