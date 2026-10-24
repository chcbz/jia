package cn.jia.agent.security;

import cn.jia.agent.api.PersonalWorkspaceRuntimeFileController;
import cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityAliasEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillExecutionService;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.core.context.EsContextHolder;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual HttpSecurity bean -> FilterChainProxy -> real runtime authentication service.
 * Only persistent identity collaborators are mocked. Formal execution belongs to Flow. */
class AgentRuntimeSecurityIntegrationTest {
    static final String TENANT = "0";
    static final String OWNER_A = "owner-a", OWNER_B = "owner-b";
    static final String CLIENT_A = "client-a", CLIENT_B = "client-b";
    static final String A = "agt_" + "a".repeat(32), B = "agt_" + "b".repeat(32);
    static final String LEGACY = "jyt-jiafewnnv58ec2379c-wuyong";
    static final String TOKEN_A = "1".repeat(32), TOKEN_B = "2".repeat(32);

    AnnotationConfigWebApplicationContext context;
    AgentRuntimeAuthenticationService auth;
    AgentRuntimeDao rows;
    AgentIdentityRegistryDao identityRegistryDao;
    AgentIdentityAliasDao identityAliasDao;
    AgentPersonaBindingDao identityBindingDao;
    ApiKeyService keys; // Legacy management collaborator is intentionally not execution authority.
    cn.jia.agent.dao.AgentRuntimeV1InstallationDao installations;
    final Map<String, cn.jia.agent.entity.AgentRuntimeV1InstallationEntity> installationRows = new HashMap<>();
    AccountSecurityService accounts;
    PersonalWorkspaceExecutionService workspaceExecutions;
    MockMvc mvc;
    MockMvc runtimeFailureMvc;
    MockMvc conversationMvc;
    final Map<String, AgentRuntimeEntity> persisted = new HashMap<>();
    final Map<Long, AgentPersonaBindingEntity> persistedBindings = new HashMap<>();
    final Map<Long, AgentIdentityRegistryEntity> persistedIdentitiesByBinding = new HashMap<>();
    final Map<String, AgentIdentityRegistryEntity> persistedIdentitiesByCanonical = new HashMap<>();
    final Map<String, AgentIdentityAliasEntity> persistedAliases = new HashMap<>();
    final AtomicBoolean openA = new AtomicBoolean(true);

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @Import(AgentRuntimeSecurityConfiguration.class)
    static class Wiring {
        @Bean AgentRuntimeDao rows() { return mock(AgentRuntimeDao.class); }
        @Bean AgentIdentityRegistryDao identityRegistryDao() { return mock(AgentIdentityRegistryDao.class); }
        @Bean AgentIdentityAliasDao identityAliasDao() { return mock(AgentIdentityAliasDao.class); }
        @Bean AgentPersonaBindingDao identityBindingDao() { return mock(AgentPersonaBindingDao.class); }
        @Bean AgentIdentityService identities(AgentIdentityRegistryDao registry,
                AgentIdentityAliasDao aliases, AgentPersonaBindingDao bindings) {
            return new AgentIdentityServiceImpl(registry, aliases, bindings);
        }
        @Bean ApiKeyService keys() { return mock(ApiKeyService.class); }
        @Bean AccountSecurityService accounts() { return mock(AccountSecurityService.class); }
        @Bean PersonalWorkspaceExecutionService workspaceExecutions() {
            return mock(PersonalWorkspaceExecutionService.class, withSettings().extraInterfaces(SkillExecutionService.class));
        }
        @Bean AgentTaskEventsGate gate() {
            return new AgentTaskEventsGate(new AgentTaskEventsProperties(true,
                    List.of(new AgentTaskEventsProperties.AllowedScope(TENANT, CLIENT_A),
                            new AgentTaskEventsProperties.AllowedScope(TENANT, CLIENT_B))));
        }
        @Bean cn.jia.agent.dao.AgentRuntimeV1InstallationDao installations() { return mock(cn.jia.agent.dao.AgentRuntimeV1InstallationDao.class); }
        @Bean AgentRuntimeAuthenticationService auth(AgentRuntimeDao r, AgentIdentityService i,
                cn.jia.agent.dao.AgentRuntimeV1InstallationDao installations, AgentIdentityRegistryDao registry,
                AccountSecurityService a, AgentTaskEventsGate g) {
            return new AgentRuntimeAuthenticationService(r, installations, registry, i, a, g);
        }
    }

    @RestController
    static final class RuntimeScopeProbeController {
        @GetMapping("/agent/tasks/{taskId}/context-pack")
        Map<String, String> contextPack(Authentication authentication) {
            return projection(authentication);
        }

        @PostMapping({
                "/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease",
                "/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/start",
                "/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/heartbeat"
        })
        Map<String, String> lease(Authentication authentication) {
            return projection(authentication);
        }

        @GetMapping({
                "/internal/agent/tasks/{taskId}/runs/{runId}/inputs",
                "/internal/agent/tasks/{taskId}/runs/{runId}/inputs/{inputId}/content"
        })
        Map<String, String> runtimeInputs(Authentication authentication) {
            return projection(authentication);
        }

        @GetMapping("/internal/agent/tasks/workspace-executions/commands")
        Map<String, String> runtimeQueuedCommands(Authentication authentication) {
            return projection(authentication);
        }

        @PostMapping({
                "/internal/agent/tasks/{taskId}/runs/{runId}/outputs/{outputId}/content",
                "/internal/agent/tasks/{taskId}/runs/{runId}/output-commits/{manifestId}"
        })
        Map<String, String> runtimeOutputs(Authentication authentication) {
            return projection(authentication);
        }

        private static Map<String, String> projection(Authentication authentication) {
            assertInstanceOf(AgentRuntimeAuthentication.class, authentication);
            var scope = ((AgentRuntimeAuthentication) authentication).getPrincipal();
            return Map.of(
                    "tenantId", scope.tenantId(),
                    "clientId", scope.clientId(),
                    "ownerJiacn", scope.ownerJiacn(),
                    "agentId", scope.agentId(),
                    "runtimeInstanceId", scope.runtimeInstanceId(),
                    "contextJiacn", EsContextHolder.getContext().getJiacn(),
                    "contextClientId", EsContextHolder.getContext().getClientId());
        }
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Wiring.class);
        context.refresh();
        auth = context.getBean(AgentRuntimeAuthenticationService.class);
        rows = context.getBean(AgentRuntimeDao.class);
        identityRegistryDao = context.getBean(AgentIdentityRegistryDao.class);
        identityAliasDao = context.getBean(AgentIdentityAliasDao.class);
        identityBindingDao = context.getBean(AgentPersonaBindingDao.class);
        keys = context.getBean(ApiKeyService.class);
        installations = context.getBean(cn.jia.agent.dao.AgentRuntimeV1InstallationDao.class);
        when(installations.lock(anyString())).thenAnswer(inv -> installationRows.get(inv.getArgument(0)));
        when(installations.findInScope(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            var row = installationRows.get(inv.getArgument(2));
            return row != null && inv.getArgument(0).equals(row.getTenantId()) && inv.getArgument(1).equals(row.getClientId()) ? row : null;
        });
        when(rows.findInScope(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            var row = persisted.get(inv.getArgument(2));
            return row != null && inv.getArgument(0).equals(row.getTenantId()) && inv.getArgument(1).equals(row.getClientId()) ? row : null;
        });
        when(rows.lockInScope(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            var row=persisted.get(inv.getArgument(2));
            return row!=null && inv.getArgument(0).equals(row.getTenantId())
                    && inv.getArgument(1).equals(row.getClientId()) ? row : null;
        });
        accounts = context.getBean(AccountSecurityService.class);
        workspaceExecutions = context.getBean(PersonalWorkspaceExecutionService.class);
        when(rows.findByAgentId(anyString())).thenAnswer(inv -> persisted.get(inv.getArgument(0)));
        when(identityRegistryDao.findExactByBindingInScope(
                anyString(), anyString(), anyString(), anyLong())).thenAnswer(inv -> scopedIdentityByBinding(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        when(identityRegistryDao.findExactByCanonicalInScope(
                anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> scopedIdentityByCanonical(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        when(identityBindingDao.selectById(anyLong())).thenAnswer(inv -> persistedBindings.get(inv.getArgument(0)));
        when(identityBindingDao.findByIdForUpdate(anyLong())).thenAnswer(inv -> persistedBindings.get(inv.getArgument(0)));
        when(identityAliasDao.findExactActiveLegacyAlias(
                anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
                    AgentIdentityAliasEntity alias = persistedAliases.get(inv.getArgument(3));
                    return alias != null && exactScope(alias, inv.getArgument(0), inv.getArgument(1), inv.getArgument(2))
                            ? alias : null;
                });
        seed(A, OWNER_A, "a", TOKEN_A);
        seed(B, OWNER_B, "b", TOKEN_B);
        bind(auth, "socket-a", CLIENT_A, OWNER_A, A, "runtime-a", "key-a", TOKEN_A, openA::get);
        bind(auth, "socket-b", CLIENT_B, OWNER_B, B, "runtime-b", "key-b", TOKEN_B, () -> true);
        FilterChainProxy filters = context.getBean(FilterChainProxy.class);
        mvc = MockMvcBuilders.standaloneSetup(new RuntimeScopeProbeController())
                .addFilters(filters).build();
        runtimeFailureMvc = MockMvcBuilders.standaloneSetup(
                        new PersonalWorkspaceRuntimeFileController(workspaceExecutions,auth))
                .addFilters(filters).build();
        conversationMvc = MockMvcBuilders.standaloneSetup(
                        new PersonalWorkspaceConversationRuntimeController(workspaceExecutions,auth))
                .addFilters(filters).build();
    }

    @AfterEach
    void close() {
        context.close();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        EsContextHolder.clearContext();
    }

    void seed(String agent, String owner, String suffix, String token) {
        long bindingId = suffix.equals("a") ? 1L : 2L;
        seedDirect(agent, owner, "client-" + suffix, bindingId,
                "key-" + suffix, token, AgentConstants.IDENTITY_TYPE_OPAQUE);
    }

    void seedDirect(String agent, String owner, String client, long bindingId, String keyId,
            String token, String canonicalType) {
        var row = new AgentRuntimeEntity().setAgentId(agent).setOwnerJiacn(owner)
                .setBindingId(bindingId).setTokenHash(verifier(owner, token)).setStatus("online")
                .setRuntimeInstallationId(installationId(bindingId)).setRuntimeHostId("host")
                .setRuntimeInstanceId("runtime-" + keyId.substring("key-".length())).setRuntimeSessionGeneration(1L);
        row.setTenantId(TENANT);
        row.setClientId(client);
        persisted.put(agent, row);
        var installation = new cn.jia.agent.entity.AgentRuntimeV1InstallationEntity()
                .setInstallationId(installationId(bindingId)).setCanonicalAgentId(agent).setStatus("ACTIVE");
        installation.setTenantId(TENANT); installation.setClientId(client);
        installationRows.put(installation.getInstallationId(), installation);
        var key = new OauthApiKeyEntity().setId(keyId).setJiacn(owner)
                .setClientId(client).setApiKey("fixture-api-key-" + keyId).setStatus(1);
        key.setTenantId(TENANT);
        when(keys.get(keyId)).thenReturn(key);
        when(accounts.findUniqueByExactJiacn(owner)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(OWNER_B.equals(owner) ? 2 : 1, owner, AccountState.ACTIVE, 0)));

        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity()
                .setId(bindingId).setJiacn(owner).setPersonaCode("persona-" + bindingId)
                .setAgentId(agent).setBoundAt(1L).setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        binding.setTenantId(TENANT);
        binding.setClientId(client);
        persistedBindings.put(bindingId, binding);
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity()
                .setId(100L + bindingId).setCanonicalAgentId(agent).setCanonicalType(canonicalType)
                .setLifecycleStatus(AgentConstants.IDENTITY_STATUS_ACTIVE).setOwnerJiacn(owner)
                .setBindingId(bindingId).setProvisionedAt(1L).setActivatedAt(2L)
                .setAuditReason("test direct canonical identity");
        identity.setTenantId(TENANT);
        identity.setClientId(client);
        persistedIdentitiesByBinding.put(bindingId, identity);
        persistedIdentitiesByCanonical.put(agent, identity);
    }

    AgentIdentityRegistryEntity scopedIdentityByBinding(
            String tenant, String client, String owner, long bindingId) {
        AgentIdentityRegistryEntity identity = persistedIdentitiesByBinding.get(bindingId);
        return identity != null && exactScope(identity, tenant, client, owner) ? identity : null;
    }

    AgentIdentityRegistryEntity scopedIdentityByCanonical(
            String tenant, String client, String owner, String canonical) {
        AgentIdentityRegistryEntity identity = persistedIdentitiesByCanonical.get(canonical);
        return identity != null && exactScope(identity, tenant, client, owner) ? identity : null;
    }

    static boolean exactScope(cn.jia.core.entity.BaseEntity value, String tenant, String client, String owner) {
        String actualOwner = value instanceof AgentIdentityRegistryEntity identity
                ? identity.getOwnerJiacn() : ((AgentIdentityAliasEntity) value).getOwnerJiacn();
        return tenant.equals(value.getTenantId()) && client.equals(value.getClientId())
                && owner.equals(actualOwner);
    }

    <B extends AbstractMockHttpServletRequestBuilder<?>> B headers(
            B request, String agent, String runtime, String token) {
        request.header("Authorization", "AgentRuntime " + credential(token))
                .header("X-Agent-Id", agent).header("X-Agent-Runtime-Id", runtime)
                .header("X-Agent-Installation-Id", installationFor(agent)).header("X-Agent-Host-Id", "host")
                .header("X-Agent-Session-Generation", persisted.containsKey(agent) && persisted.get(agent).getRuntimeSessionGeneration() != null
                        ? persisted.get(agent).getRuntimeSessionGeneration().toString() : "1");
        return request;
    }

    static String credential(String token) { return token.startsWith("rts1_") ? token : "rts1_" + token.repeat(2); }
    static String installationId(long id) { return "rti_" + "%032x".formatted(id); }
    String installationFor(String agent) {
        var row = persisted.get(agent);
        return row != null && row.getRuntimeInstallationId() != null ? row.getRuntimeInstallationId() : installationId(999);
    }
    static String verifier(String owner, String token) {
        return "urs1:" + AgentRuntimeAuthenticationService.digestHex(credential(token)) + ":" + (OWNER_B.equals(owner) ? 2 : 1) + ":0";
    }
    AgentRuntimeAuthenticationFilter.SessionHeaders proofHeaders(String agent, String runtime, String token) {
        var row = persisted.get(agent);
        return new AgentRuntimeAuthenticationFilter.SessionHeaders(agent, installationFor(agent), "host", runtime,
                row != null && row.getRuntimeSessionGeneration() != null ? row.getRuntimeSessionGeneration() : 1, credential(token));
    }
    AgentRuntimeAuthenticationService.Receipt bind(AgentRuntimeAuthenticationService service, String session,
            String client, String owner, String agent, String runtime, String legacyManagementKey, String token,
            java.util.function.BooleanSupplier connected) {
        var proof = service.verify(proofHeaders(agent, runtime, token));
        if (!client.equals(proof.scope().clientId()) || !owner.equals(proof.scope().ownerJiacn())) throw new IllegalArgumentException("wrong scope");
        return service.bind(session, proof, connected);
    }
    AgentRuntimeAuthentication authenticate(AgentRuntimeAuthenticationService service, String agent, String runtime, String token) {
        return service.authenticate(proofHeaders(agent, runtime, token), true);
    }

    JwtAuthenticationToken browserJwt() {
        Jwt jwt = Jwt.withTokenValue("browser-jwt").header("alg", "none")
                .claim("jiacn", OWNER_A).claim("client_id", CLIENT_A)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        return authentication;
    }

    MockHttpServletRequestBuilder requestA() {
        return headers(get("/agent/tasks/task-a/context-pack"), A, "runtime-a", TOKEN_A);
    }





    @RestController
    static final class ArchivePositionProbe {
        @GetMapping({"/internal/archive/v1/jobs", "/internal/archive/v1/jobs/{id}/source", "/internal/archive/v1/jobs/{id}/result"})
        Map<String,String> get(Authentication a) { return Map.of("agent",a.getName()); }
        @PostMapping("/internal/archive/v1/jobs/{id}/result")
        Map<String,String> post(Authentication a) { return Map.of("agent",a.getName()); }
    }
    @Test void archiveNativeLaneRejectsJwtApiKeysStaleSessionsAndMethodPathExpansion() throws Exception {
        var archive=MockMvcBuilders.standaloneSetup(new ArchivePositionProbe()).addFilters(context.getBean(FilterChainProxy.class)).build();
        for(String path:List.of("/internal/archive/v1/jobs","/internal/archive/v1/jobs/job-1/source","/internal/archive/v1/jobs/job-1/result")) {
            archive.perform(headers(get(path),A,"runtime-a",TOKEN_A)).andExpect(status().isOk());
            archive.perform(get(path).header("Authorization","Bearer browser-jwt")).andExpect(status().isUnauthorized());
            archive.perform(get(path).header("X-API-Key","old-secret")).andExpect(status().isUnauthorized());
            archive.perform(headers(get(path),A,"runtime-b",TOKEN_A)).andExpect(status().isUnauthorized());
        }
        archive.perform(headers(post("/internal/archive/v1/jobs/job-1/result"),A,"runtime-a",TOKEN_A)).andExpect(status().isOk());
        for(String path:List.of("/internal/archive/v1/jobs","/internal/archive/v1/jobs/job-1/source","/internal/archive/v1/jobs/job-1/result/extra"))
            archive.perform(headers(post(path),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
        verifyNoInteractions(keys);
    }
    @Test void positionNativeCommandsRequireCurrentRuntimeEvenForBrowserJwtAndReceiptRecovery() throws Exception {
        var service=mock(cn.jia.agent.position.PositionSkillRuntimeService.class);
        when(service.prepare(any(),eq(A))).thenReturn(List.of());when(service.commands(any(),any())).thenReturn(List.of());
        when(service.outcome(any(),eq("grant1"))).thenReturn(Map.of("state","INSTALLED"));
        var position=MockMvcBuilders.standaloneSetup(new cn.jia.agent.api.PositionSkillRuntimeController(service,auth)).addFilters(context.getBean(FilterChainProxy.class)).build();
        for(String path:List.of("/internal/agent/position-skills","/internal/agent/position-skills/grant1/receipt")) {
            position.perform(headers(get(path),A,"runtime-a",TOKEN_A)).andExpect(status().isOk());
            position.perform(get(path).header("Authorization","Bearer browser-jwt")).andExpect(status().isUnauthorized());
            position.perform(get(path).header("X-API-Key","legacy")).andExpect(status().isUnauthorized());
            position.perform(headers(get(path),A,"runtime-b",TOKEN_A)).andExpect(status().isUnauthorized());
        }
        String results="/internal/agent/position-skills/results";
        position.perform(headers(post(results),A,"runtime-a",TOKEN_A).contentType("application/json").content("{\"protocolVersion\":1,\"requestId\":\"position-result\",\"data\":{}}"))
                .andExpect(status().isOk());
        position.perform(post(results).header("Authorization","Bearer browser-jwt")).andExpect(status().isUnauthorized());
        position.perform(headers(post(results),A,"runtime-b",TOKEN_A)).andExpect(status().isUnauthorized());
        position.perform(headers(post("/internal/agent/position-skills/grant1/receipt"),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
        position.perform(headers(get("/internal/agent/position-skills/grant1/receipt/extra"),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
        verifyNoInteractions(keys);
    }

    @Test
    void skillPackageUsesActualNativeChainAndNeverLegacyKeyOrProductInstallAsRuntimeProof() throws Exception {
        var installs=mock(cn.jia.agent.skill.SkillManagementService.class);
        var controller=new cn.jia.agent.api.SkillPackageController(installs);
        var packageMvc=MockMvcBuilders.standaloneSetup(controller).addFilters(context.getBean(FilterChainProxy.class)).build();
        String path="/internal/agent/skill-installations/si_original/package";
        when(installs.packageBytes(any(),eq("si_original"))).thenReturn(new byte[]{1,2,3});
        packageMvc.perform(headers(get(path),A,"runtime-a",TOKEN_A))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        var proof=org.mockito.ArgumentCaptor.forClass(AgentRuntimeAuthenticationService.Proof.class);
        verify(installs).packageBytes(proof.capture(),eq("si_original"));
        assertEquals(A,proof.getValue().scope().agentId());assertNotEquals("si_original",proof.getValue().installationId());
        packageMvc.perform(get(path).header("X-API-Key","old-secret")).andExpect(status().isUnauthorized());
        packageMvc.perform(get(path)).andExpect(status().isUnauthorized());
        packageMvc.perform(headers(get(path),A,"runtime-a",TOKEN_A).header("X-Agent-Installation-Id","si_original"))
                .andExpect(status().isUnauthorized());
        verifyNoMoreInteractions(installs);verifyNoInteractions(keys);
    }



    @Test
    void installationRevokedDuringFileSourceReadCannotDeliverPreparedBytes() throws Exception {
        var scope=new PersonalWorkspaceExecutionService.RuntimeScope(TENANT,CLIENT_A,OWNER_A,A,"runtime-a");
        String source="revoked-private-file";
        when(workspaceExecutions.runtimeInputContent(scope,"task-a","run-a","input_1")).thenAnswer(call->{
            installationRows.get(installationFor(A)).setStatus("REVOKED");
            return new PersonalWorkspaceExecutionService.RuntimeContent("file.png","image/png",source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        var response=runtimeFailureMvc.perform(headers(get("/internal/agent/tasks/task-a/runs/run-a/inputs/input_1/content"),A,"runtime-a",TOKEN_A))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains(source));
        verify(workspaceExecutions).runtimeInputContent(scope,"task-a","run-a","input_1");
    }

    @Test
    void retiredNativeProviderStartCannotExecuteEvenWithAuthenticRuntime() throws Exception {
        String path="/internal/agent/tasks/task-a/runs/run-a/conversation/provider-start";
        String body="{\"version\":1,\"token\":\"01234567-89ab-cdef-0123-456789abcdef\"}";
        conversationMvc.perform(headers(post(path),A,"runtime-a",TOKEN_A).contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        conversationMvc.perform(headers(post(path),A,"runtime-b",TOKEN_A).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        conversationMvc.perform(headers(post(path),A,"runtime-a",TOKEN_A).header("Origin","https://browser.invalid")
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        conversationMvc.perform(headers(post(path+"/extra"),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
        verifyNoInteractions(workspaceExecutions);
    }

    @Test
    void retiredControlledProviderStartsRejectAllBodiesAndDoNotAuthorizeCost() throws Exception {
        for(String suffix:List.of("provider-start-controlled-image","provider-start-controlled-image-v3")) {
            String path="/internal/agent/tasks/task-a/runs/run-a/conversation/"+suffix;
            for(String body:List.of("{}","{\"schemaVersion\":2}","{\"schemaVersion\":3,\"consentId\":\"forged\"}"))
                conversationMvc.perform(headers(post(path),A,"runtime-a",TOKEN_A).contentType("application/json").content(body))
                        .andExpect(status().isNotFound());
            conversationMvc.perform(headers(post(path),A,"runtime-b",TOKEN_A).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            conversationMvc.perform(headers(post(path+"/extra"),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(workspaceExecutions);
    }




    @Test
    void persistedLegacyCanonicalRegistrationAuthorizesClosedRuntimeRoutesWithFullScope() throws Exception {
        seedDirect(LEGACY, OWNER_A, CLIENT_A, 3L, "key-legacy", "3".repeat(32),
                AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        var receipt = bind(auth, "socket-legacy", CLIENT_A, OWNER_A, LEGACY,
                "runtime-legacy", "key-legacy", "3".repeat(32), () -> true);
        assertEquals("native-runtime-v1", receipt.scheme());
        assertEquals(TENANT, receipt.tenantId());
        assertEquals(OWNER_A, receipt.ownerJiacn());
        assertEquals(LEGACY, receipt.agentId());

        mvc.perform(headers(get("/agent/tasks/task-legacy/context-pack"), LEGACY,
                        "runtime-legacy", "3".repeat(32)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.clientId").value(CLIENT_A))
                .andExpect(jsonPath("$.ownerJiacn").value(OWNER_A))
                .andExpect(jsonPath("$.agentId").value(LEGACY));

        String path = "/agent/tasks/task-legacy/work-items/work-legacy/reassignments/rsn-legacy/lease";
        for (String suffix : List.of("", "/start", "/heartbeat")) {
            mvc.perform(headers(post(path + suffix), LEGACY, "runtime-legacy", "3".repeat(32))
                            .queryParam("actorAgentId", B)
                            .header("X-Owner-Jiacn", OWNER_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ownerJiacn").value(OWNER_A))
                    .andExpect(jsonPath("$.agentId").value(LEGACY));
        }
    }

    @Test
    void aliasSystemUnknownInactiveCrossScopeAndLegacyOwnerTenantRegistrationsFail() {
        for (String invalid : List.of("invalid agent id", "agent/invalid", "a".repeat(101))) {
            assertThrows(RuntimeException.class, () -> bind(auth, "invalid-wire", CLIENT_A, OWNER_A,
                    invalid, "runtime-invalid", "key-a", TOKEN_A, () -> true));
        }

        String alias = "legacy-approved-alias";
        String aliasCanonical = "agt_" + "c".repeat(32);
        seedDirect(aliasCanonical, OWNER_A, CLIENT_A, 10L, "key-alias", "4".repeat(32),
                AgentConstants.IDENTITY_TYPE_OPAQUE);
        persisted.remove(aliasCanonical);
        AgentPersonaBindingEntity aliasBinding = persistedBindings.get(10L).setAgentId(alias);
        AgentIdentityAliasEntity aliasRow = new AgentIdentityAliasEntity().setId(11L).setRegistryId(110L)
                .setCanonicalAgentId(aliasCanonical).setAliasType(AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID)
                .setAliasValue(alias).setAliasStatus(AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE)
                .setValidFrom(1L).setOwnerJiacn(OWNER_A).setAuditReason("approved alias");
        aliasRow.setTenantId(TENANT);
        aliasRow.setClientId(CLIENT_A);
        persistedAliases.put(alias, aliasRow);
        var aliasRuntime = new AgentRuntimeEntity().setAgentId(alias).setOwnerJiacn(OWNER_A)
                .setBindingId(aliasBinding.getId()).setTokenHash("4".repeat(32)).setStatus("online");
        aliasRuntime.setTenantId(TENANT);
        aliasRuntime.setClientId(CLIENT_A);
        persisted.put(alias, aliasRuntime);
        assertThrows(RuntimeException.class, () -> bind(auth, "alias", CLIENT_A, OWNER_A, alias,
                "runtime-alias", "key-alias", "4".repeat(32), () -> true));

        seedDirect(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, OWNER_A, CLIENT_A, 12L,
                "key-system", "5".repeat(32), AgentConstants.IDENTITY_TYPE_SYSTEM);
        assertThrows(RuntimeException.class, () -> bind(auth, "system", CLIENT_A, OWNER_A,
                AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, "runtime-system", "key-system",
                "5".repeat(32), () -> true));

        seedDirect("noncanonical-direct", OWNER_A, CLIENT_A, 16L, "key-noncanonical",
                "9".repeat(32), "ALIAS");
        assertThrows(RuntimeException.class, () -> bind(auth, "noncanonical", CLIENT_A, OWNER_A,
                "noncanonical-direct", "runtime-noncanonical", "key-noncanonical",
                "9".repeat(32), () -> true));

        seedDirect("unknown-direct-id", OWNER_A, CLIENT_A, 13L, "key-unknown",
                "6".repeat(32), AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        persistedIdentitiesByBinding.remove(13L);
        persistedIdentitiesByCanonical.remove("unknown-direct-id");
        assertThrows(RuntimeException.class, () -> bind(auth, "unknown", CLIENT_A, OWNER_A,
                "unknown-direct-id", "runtime-unknown", "key-unknown", "6".repeat(32), () -> true));

        seedDirect("jyt-inactive", OWNER_A, CLIENT_A, 14L, "key-inactive",
                "7".repeat(32), AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        persistedIdentitiesByBinding.get(14L)
                .setLifecycleStatus(AgentConstants.IDENTITY_STATUS_SUSPENDED).setSuspendedAt(3L);
        persistedBindings.get(14L).setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        assertThrows(RuntimeException.class, () -> bind(auth, "inactive", CLIENT_A, OWNER_A,
                "jyt-inactive", "runtime-inactive", "key-inactive", "7".repeat(32), () -> true));

        seedDirect("jyt-cross-scope", OWNER_A, CLIENT_A, 15L, "key-cross-original",
                "8".repeat(32), AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        persisted.get("jyt-cross-scope").setClientId(CLIENT_B);
        var crossKey = new OauthApiKeyEntity().setId("key-cross").setJiacn(OWNER_A)
                .setClientId(CLIENT_B).setApiKey("fixture-api-key-cross").setStatus(1);
        crossKey.setTenantId(TENANT);
        when(keys.get("key-cross")).thenReturn(crossKey);
        assertThrows(RuntimeException.class, () -> bind(auth, "cross", CLIENT_B, OWNER_A,
                "jyt-cross-scope", "runtime-cross", "key-cross", "8".repeat(32), () -> true));

        seedDirect("jyt-legacy-owner-tenant", OWNER_A, CLIENT_A, 17L, "key-legacy-tenant",
                "a".repeat(32), AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        persisted.get("jyt-legacy-owner-tenant").setTenantId(OWNER_A);
        keys.get("key-legacy-tenant").setTenantId(OWNER_A);
        persistedBindings.get(17L).setTenantId(OWNER_A);
        persistedIdentitiesByBinding.get(17L).setTenantId(OWNER_A);
        assertThrows(RuntimeException.class, () -> bind(auth, "legacy-owner-tenant", CLIENT_A, OWNER_A,
                "jyt-legacy-owner-tenant", "runtime-legacy-tenant", "key-legacy-tenant",
                "a".repeat(32), () -> true));
    }

    @Test
    void twoAgentsUseOnlyAuthenticatedRegistrationScopeAndHttpCannotOverrideIt() throws Exception {
        for (var entry : List.of(
                List.of(A, CLIENT_A, OWNER_A, "runtime-a", TOKEN_A),
                List.of(B, CLIENT_B, OWNER_B, "runtime-b", TOKEN_B))) {
            mvc.perform(headers(get("/agent/tasks/task/context-pack"),
                            entry.get(0), entry.get(3), entry.get(4))
                            .queryParam("tenantId", OWNER_B)
                            .queryParam("clientId", CLIENT_A)
                            .queryParam("ownerJiacn", OWNER_A)
                            .header("X-Tenant-Id", OWNER_B)
                            .header("X-Client-Id", CLIENT_A)
                            .header("X-Owner-Jiacn", OWNER_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(TENANT))
                    .andExpect(jsonPath("$.clientId").value(entry.get(1)))
                    .andExpect(jsonPath("$.ownerJiacn").value(entry.get(2)))
                    .andExpect(jsonPath("$.agentId").value(entry.get(0)));
        }
        mvc.perform(headers(get("/agent/tasks/task-b/context-pack"), B, "runtime-b", TOKEN_A))
                .andExpect(status().isUnauthorized());
        mvc.perform(headers(get("/agent/tasks/task-a/context-pack"), A, "runtime-b", TOKEN_A))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allThreeLeasePathsKeepAuthenticatedAgentAndOwnerDespiteNominatedValues() throws Exception {
        String path = "/agent/tasks/task-a/work-items/work-a/reassignments/rsn-a/lease";
        for (String suffix : List.of("", "/start", "/heartbeat")) {
            mvc.perform(headers(post(path + suffix), A, "runtime-a", TOKEN_A)
                            .queryParam("actorAgentId", B)
                            .queryParam("ownerJiacn", OWNER_B)
                            .header("X-Owner-Jiacn", OWNER_B))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(TENANT))
                    .andExpect(jsonPath("$.ownerJiacn").value(OWNER_A))
                    .andExpect(jsonPath("$.agentId").value(A));
        }
    }

    @Test
    void privateWorkspaceQueuePathIsExactAndNativeOnly() throws Exception {
        String path = "/internal/agent/tasks/workspace-executions/commands";
        mvc.perform(headers(get(path), A, "runtime-a", TOKEN_A))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agentId").value(A));
        mvc.perform(headers(post(path), A, "runtime-a", TOKEN_A)).andExpect(status().isForbidden());
        mvc.perform(headers(get(path + "/extra"), A, "runtime-a", TOKEN_A)).andExpect(status().isForbidden());
        mvc.perform(headers(get(path), A, "runtime-a", TOKEN_A).header("Origin", "https://browser.invalid"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", "Bearer other")
                        .header("X-Agent-Id", A).header("X-Agent-Runtime-Id", "runtime-a"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void privateWorkspaceRuntimeFilePathsStayExactAndNativeOnly() throws Exception {
        for (String path : List.of("/internal/agent/tasks/task-a/runs/run-a/inputs",
                "/internal/agent/tasks/task-a/runs/run-a/inputs/input-a/content")) {
            mvc.perform(headers(get(path), A, "runtime-a", TOKEN_A))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.agentId").value(A));
            mvc.perform(headers(post(path), A, "runtime-a", TOKEN_A)).andExpect(status().isForbidden());
        }
        for (String path : List.of("/internal/agent/tasks/task-a/runs/run-a/outputs/output-a/content",
                "/internal/agent/tasks/task-a/runs/run-a/output-commits/manifest-a")) {
            mvc.perform(headers(post(path), A, "runtime-a", TOKEN_A))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.agentId").value(A));
            mvc.perform(headers(get(path), A, "runtime-a", TOKEN_A)).andExpect(status().isForbidden());
        }
        mvc.perform(headers(get("/internal/agent/tasks/task-a/runs/run-a/inputs/input-a/content/extra"),
                A, "runtime-a", TOKEN_A)).andExpect(status().isForbidden());
    }


    @Test
    void nonTargetMethodsPathsBrowsersAndAmbiguousHeadersAreDeniedBeforeController() throws Exception {
        for (String path : List.of("/resource", "/user/info", "/agent/tasks/task-a/work-items/work-a/reassignments",
                "/agent/tasks/task-a/context-pack/extra",
                "/agent/tasks/task-a/work-items/work-a/reassignments/r/lease/reassign")) {
            mvc.perform(headers(post(path), A, "runtime-a", TOKEN_A)).andExpect(status().isForbidden());
            mvc.perform(headers(get(path), A, "runtime-a", TOKEN_A)).andExpect(status().isForbidden());
        }
        mvc.perform(headers(post("/agent/tasks/task-a/context-pack"), A, "runtime-a", TOKEN_A))
                .andExpect(status().isForbidden());
        mvc.perform(requestA().header("Origin", "https://browser.invalid")).andExpect(status().isForbidden());
        mvc.perform(requestA().header("Authorization", "Bearer other")).andExpect(status().isUnauthorized());
        mvc.perform(requestA().header("X-Agent-Id", B)).andExpect(status().isUnauthorized());
    }

    @Test
    void revokeRotateDisconnectAndOldSocketCloseCannotAuthorizeOrKillNewBinding() throws Exception {
        persisted.get(A).setTokenHash(verifier(OWNER_A, "3".repeat(32)));
        persisted.get(A).setRuntimeInstanceId("runtime-new").setRuntimeSessionGeneration(2L);
        assertThrows(RuntimeException.class, () -> authenticate(auth, A, "runtime-a", "3".repeat(32)));
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        bind(auth, "socket-a-new", CLIENT_A, OWNER_A, A, "runtime-new", "key-a", "3".repeat(32), () -> true);
        auth.disconnect("socket-a");
        assertEquals(A, authenticate(auth, A, "runtime-new", "3".repeat(32)).getName());
        assertThrows(RuntimeException.class, () -> authenticate(auth, A, "runtime-a", TOKEN_A));
        auth.disconnect("socket-a-new");
        assertThrows(RuntimeException.class, () -> authenticate(auth, A, "runtime-new", "3".repeat(32)));
        assertEquals(B, authenticate(auth, B, "runtime-b", TOKEN_B).getName());
    }

    @Test
    void closedSocketRevokedKeyScopeDriftSuspendedIdentityAndAccountEpochAreRechecked() throws Exception {
        installationRows.get(installationId(1)).setStatus("REVOKED");
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        installationRows.get(installationId(1)).setStatus("ACTIVE");
        openA.set(false);
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        openA.set(true);
        persisted.get(A).setRuntimeHostId("foreign-host");
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        persisted.get(A).setRuntimeHostId("host");
        when(accounts.findUniqueByExactJiacn(OWNER_A)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(1, OWNER_A, AccountState.ACTIVE, 1)));
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        when(accounts.findUniqueByExactJiacn(OWNER_A)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(1, OWNER_A, AccountState.ACTIVE, 0)));
        persisted.get(A).setOwnerJiacn(OWNER_B);
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        persisted.get(A).setOwnerJiacn(OWNER_A);
        persisted.get(A).setClientId(CLIENT_B);
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        persisted.get(A).setClientId(CLIENT_A);
        persistedIdentitiesByBinding.get(1L)
                .setLifecycleStatus(AgentConstants.IDENTITY_STATUS_SUSPENDED).setSuspendedAt(3L);
        persistedBindings.get(1L).setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        mvc.perform(requestA()).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"AGENT_RUNTIME_UNAUTHENTICATED\"}"));
    }

    @Test
    void proofRotatedAfterHttpAdmissionCannotMutateOriginalWorkspaceRun() {
        var admitted=authenticate(auth,A,"runtime-a",TOKEN_A);
        persisted.get(A).setRuntimeSessionGeneration(2L).setRuntimeInstanceId("replacement-boot");
        var controller=new PersonalWorkspaceRuntimeFileController(workspaceExecutions,auth);
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/internal/agent/tasks/task-a/runs/run-a/failure");
        assertThrows(RuntimeException.class,()->controller.failure("task-a","run-a",
                new cn.jia.core.entity.JsonRequest<>(1, "test-request", new PersonalWorkspaceRuntimeFileController.FailureRequest("OUTPUT_MISSING")),request,admitted));
        verifyNoInteractions(workspaceExecutions);
    }

    @Test
    void disabledCapabilityIsReflectedWithoutChangingAuthenticatedScope() {
        var disabledGate = new AgentTaskEventsGate(new AgentTaskEventsProperties(false, List.of()));
        var scopedAuth = new AgentRuntimeAuthenticationService(rows,
                installations, identityRegistryDao, context.getBean(AgentIdentityService.class), accounts, disabledGate);
        var receipt = bind(scopedAuth, "disabled", CLIENT_A, OWNER_A, A,
                "runtime-a", "key-a", TOKEN_A, () -> true);
        assertFalse(receipt.contextPackEnabled());
        var principal = authenticate(scopedAuth, A, "runtime-a", TOKEN_A).getPrincipal();
        assertEquals(TENANT, principal.tenantId());
        assertEquals(CLIENT_A, principal.clientId());
        assertEquals(OWNER_A, principal.ownerJiacn());
    }

    @Test
    void accountReplacementMissingBindingAndWrongOwnerFailClosed() throws Exception {
        when(accounts.findUniqueByExactJiacn(OWNER_A)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(999, OWNER_A, AccountState.ACTIVE, 0)));
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        when(accounts.findUniqueByExactJiacn(OWNER_A)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(1, OWNER_A, AccountState.ACTIVE, 0)));
        persistedBindings.remove(1L);
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        assertThrows(RuntimeException.class, () -> bind(auth, "wrong-owner", CLIENT_A, OWNER_B,
                A, "runtime-other", "key-a", TOKEN_A, () -> true));
    }

    @Test
    void domainContextContainsAuthenticatedOwnerAndClientAndIsRestoredAfterRequest() throws Exception {
        var previous = new cn.jia.core.context.EsContext();
        previous.setJiacn("cookie-owner");
        previous.setClientId("cookie-client");
        EsContextHolder.setContext(previous);
        try {
            mvc.perform(requestA())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tenantId").value(TENANT))
                    .andExpect(jsonPath("$.ownerJiacn").value(OWNER_A))
                    .andExpect(jsonPath("$.contextJiacn").value(OWNER_A))
                    .andExpect(jsonPath("$.contextClientId").value(CLIENT_A));
            assertSame(previous, EsContextHolder.getContext());
        } finally {
            EsContextHolder.clearContext();
        }
    }

    @Test
    void runtimeV1ClientLaneIsPostOnlyAndExcludesScopedInstallationAdministration() {
        var servletContext = new MockServletContext();
        for (String path : List.of("/agent/runtime/v1/enroll", "/agent/runtime/v1/session",
                "/agent/runtime/v1/heartbeat")) {
            assertTrue(AgentRuntimeSecurityConfiguration.selectsRuntimeV1ClientLane(
                    post(path).buildRequest(servletContext)));
            assertFalse(AgentRuntimeSecurityConfiguration.selectsRuntimeV1ClientLane(
                    get(path).buildRequest(servletContext)));
        }
        for (String path : List.of("/agent/runtime/v1/installations", "/agent/runtime/v1/installations/id-1/revoke",
                "/agent/runtime/v1/commands/message-1/acks/extra", "/agent/runtime/v1/commands//acks")) {
            assertFalse(AgentRuntimeSecurityConfiguration.selectsRuntimeV1ClientLane(
                    post(path).buildRequest(servletContext)));
        }
    }

    @Test
    void selectorClaimsExclusiveRuntimeWebSocketLaneAndRejectsLegacyApiKeyCredentials() throws Exception {
        var servletContext = new MockServletContext();
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/ws/agent/channel").header("X-API-Key", "api-key")
                        .header("X-Agent-Id", A).buildRequest(servletContext)));
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/ws/agent/channel").queryParam("api_key", "api-key")
                        .queryParam("agentId", A).buildRequest(servletContext)));
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/ws/agent/channel").header("X-Agent-Id", A).header("X-Agent-Id", B)
                        .buildRequest(servletContext)));
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/ws/agent/channel").header("X-Agent-Runtime-Id", "runtime-a")
                        .buildRequest(servletContext)));
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/ws/agent/channel").header("Authorization", "AgentRuntime " + TOKEN_A)
                        .buildRequest(servletContext)));
        assertTrue(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/ws/agent/channel").header("Authorization", "AgentRuntimeMalformed")
                        .buildRequest(servletContext)));

        mvc.perform(get("/ws/agent/channel").header("X-API-Key","api-key").header("X-Agent-Id",A))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AGENT_RUNTIME_UNAUTHENTICATED"));
        mvc.perform(get("/ws/agent/channel").queryParam("api_key","api-key").queryParam("agentId",A))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AGENT_RUNTIME_UNAUTHENTICATED"));
        verifyNoInteractions(keys);

        mvc.perform(get("/agent/tasks/task-a/context-pack")
                        .header("Authorization", "AgentRuntimeMalformed")
                        .header("X-Agent-Id", A).header("X-Agent-Runtime-Id", "runtime-a"))
                .andExpect(status().isUnauthorized());
        mvc.perform(requestA().header("X-Agent-Runtime-Id", "runtime-duplicate"))
                .andExpect(status().isUnauthorized());
        mvc.perform(requestA().header("Authorization", "AgentRuntime " + TOKEN_B))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noMintedJwtAndBearerNeverSelectsTheRuntimeCredentialLane() {
        var principal = authenticate(auth, A, "runtime-a", TOKEN_A);
        assertNull(principal.getCredentials());
        assertFalse(principal.toString().contains(TOKEN_A));
        assertEquals(TENANT, principal.getPrincipal().tenantId());
        assertEquals(OWNER_A, principal.getPrincipal().ownerJiacn());
        assertThrows(IllegalArgumentException.class, () -> principal.setAuthenticated(true));
        assertFalse(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/agent/tasks/task-a/context-pack")
                        .header("Authorization", "Bearer original-jwt")
                        .buildRequest(new MockServletContext())));
        assertThrows(RuntimeException.class, () -> bind(auth, "s", CLIENT_A, OWNER_B,
                A, "r", "key-a", TOKEN_A, () -> true));
    }
}
