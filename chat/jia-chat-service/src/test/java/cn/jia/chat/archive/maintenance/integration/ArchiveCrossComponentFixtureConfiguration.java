package cn.jia.chat.archive.maintenance.integration;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.security.AgentRuntimeAuthenticationFilter;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.impl.FileSystemAgentTaskArtifactStorage;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.http.ArchiveController;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.http.ArchiveAdminController;
import cn.jia.chat.archive.maintenance.http.ArchiveMaintenanceExceptionHandler;
import cn.jia.chat.archive.maintenance.http.ArchiveNativeController;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceServiceImpl;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.maintenance.store.JdbcArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveReaderServiceImpl;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.archive.service.SpringArchiveTransactions;
import cn.jia.chat.archive.store.ArchiveContentStore;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Configuration(proxyBeanMethods = false)
@EnableWebMvc
public class ArchiveCrossComponentFixtureConfiguration {
    public static final String TENANT = "0";
    public static final String CLIENT = "client-a";
    public static final String OWNER = "owner-a";
    public static final String AGENT = "agent-a";
    public static final String RUNTIME = "runtime-a";
    public static final long BINDING = 7L;
    public static final String API_KEY_ID = "fixture-api-key";
    public static final String SESSION_ID = "fixture-session";

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(required("CYF_H02_MYSQL_URL"));
        source.setUsername(System.getenv().getOrDefault("CYF_H02_MYSQL_USER", "root"));
        source.setPassword(System.getenv().getOrDefault("CYF_H02_MYSQL_PASSWORD", ""));
        return source;
    }

    @Bean public JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
    @Bean public DataSourceTransactionManager transactionManager(DataSource source) {
        return new DataSourceTransactionManager(source);
    }
    @Bean public ArchiveTransactions archiveTransactions(DataSourceTransactionManager manager) {
        return new SpringArchiveTransactions(manager);
    }
    @Bean public ArchiveMaintenanceStore archiveMaintenanceStore(JdbcTemplate jdbc) {
        return new JdbcArchiveMaintenanceStore(jdbc);
    }
    @Bean public ArchiveContentStore archiveContentStore(JdbcTemplate jdbc) {
        return new JdbcArchiveContentStore(jdbc);
    }

    @Bean
    public AgentTaskArtifactStorage artifactStorage() {
        return new FileSystemAgentTaskArtifactStorage(
                Path.of(required("CYF_FIXTURE_ARTIFACT_ROOT")).toAbsolutePath().normalize(),
                16L * 1024L * 1024L, Set.of("text/plain"));
    }

    @Bean
    public ArchiveMaintenanceProperties archiveMaintenanceProperties() {
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        return properties;
    }

    @Bean
    public ArchiveReaderAccessPolicy archiveReaderAccessPolicy() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        return ArchiveReaderAccessPolicy.from(properties);
    }

    @Bean
    public AgentIdentityService agentIdentityService() {
        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
        identity.setCanonicalAgentId(AGENT);
        identity.setOwnerJiacn(OWNER);
        identity.setBindingId(BINDING);
        identity.setLifecycleStatus("ACTIVE");
        identity.setTenantId(TENANT);
        identity.setClientId(CLIENT);
        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
        binding.setId(BINDING);
        binding.setAgentId(AGENT);
        binding.setJiacn(OWNER);
        binding.setStatus(1);
        binding.setTenantId(TENANT);
        binding.setClientId(CLIENT);
        when(identities.requireActiveIdentityForBinding(eq(TENANT), eq(CLIENT), eq(OWNER),
                eq(BINDING), eq(AGENT))).thenReturn(identity);
        when(identities.requireActiveBinding(eq(identity), nullable(String.class))).thenReturn(binding);
        when(identities.lockActiveCanonicalAgentIdsInScope(eq(TENANT), eq(CLIENT), eq(OWNER), anyList()))
                .thenAnswer(call -> List.copyOf(call.getArgument(3)));
        return identities;
    }

    @Bean
    public AgentRuntimeDao agentRuntimeDao() {
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId(AGENT);
        runtime.setOwnerJiacn(OWNER);
        runtime.setBindingId(BINDING);
        runtime.setTokenHash(required("CYF_FIXTURE_RUNTIME_TOKEN"));
        runtime.setStatus("online");
        runtime.setTenantId(TENANT);
        runtime.setClientId(CLIENT);
        when(runtimes.findByAgentId(AGENT)).thenReturn(runtime);
        return runtimes;
    }

    @Bean
    public ApiKeyService apiKeyService() {
        ApiKeyService keys = mock(ApiKeyService.class);
        OauthApiKeyEntity key = new OauthApiKeyEntity();
        key.setId(API_KEY_ID);
        key.setApiKey("fixture-key-material-never-logged");
        key.setStatus(1);
        key.setJiacn(OWNER);
        key.setTenantId(TENANT);
        key.setClientId(CLIENT);
        when(keys.get(API_KEY_ID)).thenReturn(key);
        return keys;
    }

    @Bean
    public AccountSecurityService accountSecurityService() {
        AccountSecurityService accounts = mock(AccountSecurityService.class);
        when(accounts.findUniqueByExactJiacn(OWNER)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17L, OWNER, AccountState.ACTIVE, 3L)));
        return accounts;
    }

    @Bean
    public AgentTaskEventsGate agentTaskEventsGate() {
        return new AgentTaskEventsGate(new AgentTaskEventsProperties(true,
                List.of(new AgentTaskEventsProperties.AllowedScope(TENANT, CLIENT))));
    }

    @Bean
    public AgentRuntimeAuthenticationService runtimeAuthenticationService(AgentRuntimeDao runtimes,
            AgentIdentityService identities, ApiKeyService keys, AccountSecurityService accounts,
            AgentTaskEventsGate gate) {
        return new AgentRuntimeAuthenticationService(runtimes, identities, keys, accounts, gate);
    }

    @Bean public Clock fixtureClock() { return Clock.systemUTC(); }

    @Bean
    public FixtureArchiveExecutionPort fixtureArchiveExecutionPort(
            AgentRuntimeAuthenticationService authentication, Clock clock) {
        return new FixtureArchiveExecutionPort(authentication, clock);
    }

    @Bean
    public ArchiveMaintenanceServiceImpl archiveMaintenanceService(ArchiveMaintenanceStore store,
            ArchiveContentStore content, ArchiveTransactions transactions, AgentIdentityService identities,
            ObjectMapper mapper, AgentTaskArtifactStorage storage) {
        return new ArchiveMaintenanceServiceImpl(store, content, transactions, identities, mapper, storage);
    }

    @Bean public ArchiveReaderServiceImpl archiveReaderService(ArchiveContentStore store) {
        return new ArchiveReaderServiceImpl(store);
    }
    @Bean public ArchiveAdminController archiveAdminController(ArchiveMaintenanceServiceImpl service,
            ObjectMapper mapper) { return new ArchiveAdminController(service, mapper); }
    @Bean public ArchiveNativeController archiveNativeController(ArchiveMaintenanceServiceImpl service,
            ObjectMapper mapper) { return new ArchiveNativeController(service, mapper); }
    @Bean public ArchiveMaintenanceExceptionHandler archiveMaintenanceExceptionHandler() {
        return new ArchiveMaintenanceExceptionHandler();
    }
    @Bean public ArchiveController archiveController(ArchiveReaderServiceImpl reader,
            ArchiveReaderAccessPolicy policy) { return new ArchiveController(reader, policy); }

    public static AgentRuntimeAuthenticationFilter runtimeFilter(
            AgentRuntimeAuthenticationService authentication) {
        return new AgentRuntimeAuthenticationFilter(authentication);
    }

    public static FixtureBearerFilter bearerFilter(String expectedToken) {
        return new FixtureBearerFilter(expectedToken);
    }

    public static FixtureSecurityContextRequestFilter securityContextRequestFilter() {
        return new FixtureSecurityContextRequestFilter();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException(name + " is required for the disposable fixture");
        }
        return value;
    }

    /** Exact opaque admin-token decoder double; it does not accept arbitrary JWT claims. */
    public static final class FixtureBearerFilter extends OncePerRequestFilter {
        private final String expected;
        private FixtureBearerFilter(String expected) { this.expected = expected; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String supplied = request.getHeader("Authorization");
            if (!expected.equals(supplied)) {
                response.setStatus(401);
                response.setHeader("Cache-Control", "private, no-store");
                response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"AUTH_CONTEXT_INCOMPLETE\"}");
                return;
            }
            var previous = SecurityContextHolder.getContext();
            var context = SecurityContextHolder.createEmptyContext();
            Jwt jwt = Jwt.withTokenValue("fixture-opaque")
                    .header("alg", "none").claim("jiacn", OWNER).claim("client_id", CLIENT)
                    .issuedAt(Instant.parse("2026-10-01T00:00:00Z"))
                    .expiresAt(Instant.parse("2030-10-01T00:00:00Z")).build();
            context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
            SecurityContextHolder.setContext(context);
            try { chain.doFilter(request, response); }
            finally { SecurityContextHolder.setContext(previous); }
        }
    }

    /** Makes the active test authentication visible to MVC for both admin and native routes. */
    public static final class FixtureSecurityContextRequestFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            HttpServletRequest contextAwareRequest = new HttpServletRequestWrapper(request) {
                @Override
                public java.security.Principal getUserPrincipal() {
                    return SecurityContextHolder.getContext().getAuthentication();
                }
            };
            chain.doFilter(contextAwareRequest, response);
        }
    }
}
