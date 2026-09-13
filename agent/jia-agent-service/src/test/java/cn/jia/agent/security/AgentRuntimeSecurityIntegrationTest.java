package cn.jia.agent.security;

import cn.jia.agent.api.AgentTaskContextPackController;
import cn.jia.agent.api.AgentWorkItemReassignmentController;
import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual HttpSecurity bean -> FilterChainProxy -> real runtime service -> real F01/E05 controllers.
 * Only persistent collaborators/domain operations are mocked. Formal execution belongs to Flow. */
class AgentRuntimeSecurityIntegrationTest {
    static final String A = "agt_" + "a".repeat(32), B = "agt_" + "b".repeat(32);
    static final String LEGACY = "jyt-jiafewnnv58ec2379c-wuyong";
    static final String TOKEN_A = "1".repeat(32), TOKEN_B = "2".repeat(32);
    AnnotationConfigWebApplicationContext context;
    AgentRuntimeAuthenticationService auth;
    AgentRuntimeDao rows;
    AgentIdentityRegistryDao identityRegistryDao;
    AgentIdentityAliasDao identityAliasDao;
    AgentPersonaBindingDao identityBindingDao;
    ApiKeyService keys;
    AccountSecurityService accounts;
    AgentTaskContextPackService packs;
    AgentWorkItemReassignmentService leases;
    MockMvc mvc;
    final Map<String, AgentRuntimeEntity> persisted = new HashMap<>();
    final Map<Long, AgentPersonaBindingEntity> persistedBindings = new HashMap<>();
    final Map<Long, AgentIdentityRegistryEntity> persistedIdentitiesByBinding = new HashMap<>();
    final Map<String, AgentIdentityRegistryEntity> persistedIdentitiesByCanonical = new HashMap<>();
    final Map<String, AgentIdentityAliasEntity> persistedAliases = new HashMap<>();
    final AtomicBoolean openA = new AtomicBoolean(true);

    @Configuration(proxyBeanMethods = false) @EnableWebSecurity
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
        @Bean AgentTaskContextPackService packs() { return mock(AgentTaskContextPackService.class); }
        @Bean AgentWorkItemReassignmentService leases() { return mock(AgentWorkItemReassignmentService.class); }
        @Bean AgentTaskEventsGate gate() { return new AgentTaskEventsGate(new AgentTaskEventsProperties(true,
                List.of(new AgentTaskEventsProperties.AllowedScope("tenant-a", "client-a"),
                        new AgentTaskEventsProperties.AllowedScope("tenant-b", "client-b")))); }
        @Bean AgentRuntimeAuthenticationService auth(AgentRuntimeDao r, AgentIdentityService i,
                ApiKeyService k, AccountSecurityService a, AgentTaskEventsGate g) {
            return new AgentRuntimeAuthenticationService(r, i, k, a, g);
        }
    }

    @BeforeEach void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(Wiring.class); context.refresh();
        auth = context.getBean(AgentRuntimeAuthenticationService.class);
        rows = context.getBean(AgentRuntimeDao.class);
        identityRegistryDao = context.getBean(AgentIdentityRegistryDao.class);
        identityAliasDao = context.getBean(AgentIdentityAliasDao.class);
        identityBindingDao = context.getBean(AgentPersonaBindingDao.class);
        keys = context.getBean(ApiKeyService.class);
        accounts = context.getBean(AccountSecurityService.class); packs = context.getBean(AgentTaskContextPackService.class);
        leases = context.getBean(AgentWorkItemReassignmentService.class);
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
        seed(A, "a", TOKEN_A); seed(B, "b", TOKEN_B);
        auth.bind("socket-a", "tenant-a", "client-a", A, "runtime-a", "key-a", TOKEN_A, openA::get);
        auth.bind("socket-b", "tenant-b", "client-b", B, "runtime-b", "key-b", TOKEN_B, () -> true);
        mvc = MockMvcBuilders.standaloneSetup(new AgentTaskContextPackController(packs, context.getBean(AgentTaskEventsGate.class)),
                new AgentWorkItemReassignmentController(leases))
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { context.close(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    void seed(String agent, String suffix, String token) {
        long bindingId = suffix.equals("a") ? 1L : 2L;
        seedDirect(agent, "tenant-" + suffix, "client-" + suffix, bindingId,
                "key-" + suffix, token, AgentConstants.IDENTITY_TYPE_OPAQUE);
    }

    void seedDirect(String agent, String tenant, String client, long bindingId, String keyId,
            String token, String canonicalType) {
        var row = new AgentRuntimeEntity().setAgentId(agent).setOwnerJiacn(tenant)
                .setBindingId(bindingId).setTokenHash(token).setStatus("online");
        row.setTenantId(tenant); row.setClientId(client); persisted.put(agent, row);
        var key = new OauthApiKeyEntity().setId(keyId).setJiacn(tenant)
                .setClientId(client).setApiKey("fixture-api-key-" + keyId).setStatus(1);
        key.setTenantId(tenant);
        when(keys.get(keyId)).thenReturn(key);
        when(accounts.findUniqueByExactJiacn(tenant)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(tenant.equals("tenant-b") ? 2 : 1, tenant, AccountState.ACTIVE, 0)));

        AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity()
                .setId(bindingId).setJiacn(tenant).setPersonaCode("persona-" + bindingId)
                .setAgentId(agent).setBoundAt(1L).setStatus(AgentConstants.BINDING_STATUS_ACTIVE);
        binding.setTenantId(tenant); binding.setClientId(client);
        persistedBindings.put(bindingId, binding);
        AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity()
                .setId(100L + bindingId).setCanonicalAgentId(agent).setCanonicalType(canonicalType)
                .setLifecycleStatus(AgentConstants.IDENTITY_STATUS_ACTIVE).setOwnerJiacn(tenant)
                .setBindingId(bindingId).setProvisionedAt(1L).setActivatedAt(2L)
                .setAuditReason("test direct canonical identity");
        identity.setTenantId(tenant); identity.setClientId(client);
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
    MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request, String agent, String runtime, String token) {
        return request.header("Authorization", "AgentRuntime " + token)
                .header("X-Agent-Id", agent).header("X-Agent-Runtime-Id", runtime);
    }
    MockHttpServletRequestBuilder requestA() { return headers(get("/agent/tasks/task-a/context-pack"), A, "runtime-a", TOKEN_A); }

    @Test void persistedLegacyCanonicalRegistrationAuthorizesF01AndAllE05LeaseRoutes() throws Exception {
        seedDirect(LEGACY, "tenant-a", "client-a", 3L, "key-legacy", "3".repeat(32),
                AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        var receipt = auth.bind("socket-legacy", "tenant-a", "client-a", LEGACY,
                "runtime-legacy", "key-legacy", "3".repeat(32), () -> true);
        assertEquals("native-runtime-v1", receipt.scheme());
        assertEquals(LEGACY, receipt.agentId());

        var pack = new AgentTaskContextPackDTO();
        var provenance = new AgentTaskContextPackDTO.Provenance();
        provenance.setTenantId("tenant-a"); provenance.setClientId("client-a");
        provenance.setTaskId("task-legacy"); provenance.setActorAgentId(LEGACY);
        provenance.setTaskVersion("1"); provenance.setCurrentEventVersion("7");
        pack.setProvenance(provenance);
        when(packs.generate("tenant-a", "client-a", "task-legacy", LEGACY, null)).thenReturn(pack);
        mvc.perform(headers(get("/agent/tasks/task-legacy/context-pack"), LEGACY,
                        "runtime-legacy", "3".repeat(32)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provenance.actorAgentId").value(LEGACY));

        String path = "/agent/tasks/task-legacy/work-items/work-legacy/reassignments/rsn-legacy/lease";
        for (String suffix : List.of("", "/start", "/heartbeat")) {
            mvc.perform(headers(post(path + suffix), LEGACY, "runtime-legacy", "3".repeat(32))
                            .queryParam("actorAgentId", LEGACY).contentType("application/json")
                            .content("{\"commandId\":\"cmd-legacy\",\"expectedWorkItemVersion\":5}"))
                    .andExpect(status().isOk());
        }
        verify(leases).readLease(eq("tenant-a"), eq("client-a"), eq(LEGACY),
                eq("task-legacy"), eq("work-legacy"), eq("rsn-legacy"), any());
        verify(leases).startLease(eq("tenant-a"), eq("client-a"), eq(LEGACY),
                eq("task-legacy"), eq("work-legacy"), eq("rsn-legacy"), any());
        verify(leases).heartbeatLease(eq("tenant-a"), eq("client-a"), eq(LEGACY),
                eq("task-legacy"), eq("work-legacy"), eq("rsn-legacy"), any());
    }

    @Test void aliasSystemUnknownInactiveAndCrossScopeRegistrationsFailPersistedAuthority() {
        for (String invalid : List.of("invalid agent id", "agent/invalid", "a".repeat(101))) {
            assertThrows(RuntimeException.class, () -> auth.bind("invalid-wire", "tenant-a", "client-a",
                    invalid, "runtime-invalid", "key-a", TOKEN_A, () -> true));
        }

        String alias = "legacy-approved-alias";
        String aliasCanonical = "agt_" + "c".repeat(32);
        seedDirect(aliasCanonical, "tenant-a", "client-a", 10L, "key-alias", "4".repeat(32),
                AgentConstants.IDENTITY_TYPE_OPAQUE);
        persisted.remove(aliasCanonical);
        AgentPersonaBindingEntity aliasBinding = persistedBindings.get(10L).setAgentId(alias);
        AgentIdentityAliasEntity aliasRow = new AgentIdentityAliasEntity().setId(11L).setRegistryId(110L)
                .setCanonicalAgentId(aliasCanonical).setAliasType(AgentConstants.IDENTITY_ALIAS_TYPE_LEGACY_AGENT_ID)
                .setAliasValue(alias).setAliasStatus(AgentConstants.IDENTITY_ALIAS_STATUS_ACTIVE)
                .setValidFrom(1L).setOwnerJiacn("tenant-a").setAuditReason("approved alias");
        aliasRow.setTenantId("tenant-a"); aliasRow.setClientId("client-a"); persistedAliases.put(alias, aliasRow);
        var aliasRuntime = new AgentRuntimeEntity().setAgentId(alias).setOwnerJiacn("tenant-a")
                .setBindingId(aliasBinding.getId()).setTokenHash("4".repeat(32)).setStatus("online");
        aliasRuntime.setTenantId("tenant-a"); aliasRuntime.setClientId("client-a"); persisted.put(alias, aliasRuntime);
        assertThrows(RuntimeException.class, () -> auth.bind("alias", "tenant-a", "client-a", alias,
                "runtime-alias", "key-alias", "4".repeat(32), () -> true));

        seedDirect(AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, "tenant-a", "client-a", 12L,
                "key-system", "5".repeat(32), AgentConstants.IDENTITY_TYPE_SYSTEM);
        assertThrows(RuntimeException.class, () -> auth.bind("system", "tenant-a", "client-a",
                AgentConstants.BUILTIN_SONGJIANG_AGENT_ID, "runtime-system", "key-system",
                "5".repeat(32), () -> true));

        seedDirect("noncanonical-direct", "tenant-a", "client-a", 16L, "key-noncanonical",
                "9".repeat(32), "ALIAS");
        assertThrows(RuntimeException.class, () -> auth.bind("noncanonical", "tenant-a", "client-a",
                "noncanonical-direct", "runtime-noncanonical", "key-noncanonical",
                "9".repeat(32), () -> true));

        seedDirect("unknown-direct-id", "tenant-a", "client-a", 13L, "key-unknown",
                "6".repeat(32), AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        persistedIdentitiesByBinding.remove(13L); persistedIdentitiesByCanonical.remove("unknown-direct-id");
        assertThrows(RuntimeException.class, () -> auth.bind("unknown", "tenant-a", "client-a",
                "unknown-direct-id", "runtime-unknown", "key-unknown", "6".repeat(32), () -> true));

        seedDirect("jyt-inactive", "tenant-a", "client-a", 14L, "key-inactive",
                "7".repeat(32), AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        persistedIdentitiesByBinding.get(14L)
                .setLifecycleStatus(AgentConstants.IDENTITY_STATUS_SUSPENDED).setSuspendedAt(3L);
        persistedBindings.get(14L).setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        assertThrows(RuntimeException.class, () -> auth.bind("inactive", "tenant-a", "client-a",
                "jyt-inactive", "runtime-inactive", "key-inactive", "7".repeat(32), () -> true));

        seedDirect("jyt-cross-scope", "tenant-a", "client-a", 15L, "key-cross-original",
                "8".repeat(32), AgentConstants.IDENTITY_TYPE_LEGACY_CANONICAL);
        var crossRuntime = persisted.get("jyt-cross-scope");
        crossRuntime.setClientId("client-b");
        var crossKey = new OauthApiKeyEntity().setId("key-cross").setJiacn("tenant-a")
                .setClientId("client-b").setApiKey("fixture-api-key-cross").setStatus(1);
        crossKey.setTenantId("tenant-a"); when(keys.get("key-cross")).thenReturn(crossKey);
        assertThrows(RuntimeException.class, () -> auth.bind("cross", "tenant-a", "client-b",
                "jyt-cross-scope", "runtime-cross", "key-cross", "8".repeat(32), () -> true));
    }

    @Test void twoAgentsAndTwoScopesComeOnlyFromAuthenticatedRegistration() throws Exception {
        for (var entry : List.of(List.of(A,"a",TOKEN_A), List.of(B,"b",TOKEN_B))) {
            String agent = entry.get(0), suffix = entry.get(1);
            var pack = new AgentTaskContextPackDTO(); var provenance = new AgentTaskContextPackDTO.Provenance();
            provenance.setTenantId("tenant-"+suffix); provenance.setClientId("client-"+suffix);
            provenance.setTaskId("task-"+suffix); provenance.setActorAgentId(agent);
            provenance.setTaskVersion("1"); provenance.setCurrentEventVersion("7"); pack.setProvenance(provenance);
            when(packs.generate("tenant-"+suffix,"client-"+suffix,"task-"+suffix,agent,null)).thenReturn(pack);
            mvc.perform(headers(get("/agent/tasks/task-"+suffix+"/context-pack"),agent,"runtime-"+suffix,entry.get(2)))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(jsonPath("$.provenance.actorAgentId").value(agent));
            verify(packs).generate("tenant-"+suffix,"client-"+suffix,"task-"+suffix,agent,null);
        }
        mvc.perform(requestA().queryParam("tenantId","tenant-b")).andExpect(status().isBadRequest());
        mvc.perform(headers(get("/agent/tasks/task-b/context-pack"), B,"runtime-b", TOKEN_A))
                .andExpect(status().isUnauthorized());
        mvc.perform(headers(get("/agent/tasks/task-a/context-pack"), A,"runtime-b", TOKEN_A))
                .andExpect(status().isUnauthorized());
    }

    @Test void allThreeLeasePathsUseExactTargetAndCannotDelegate() throws Exception {
        String path = "/agent/tasks/task-a/work-items/work-a/reassignments/rsn-a/lease";
        for (String suffix : List.of("", "/start", "/heartbeat")) {
            mvc.perform(headers(post(path+suffix),A,"runtime-a",TOKEN_A).queryParam("actorAgentId", A)
                    .contentType("application/json").content("{\"commandId\":\"cmd-a\",\"expectedWorkItemVersion\":5}"))
                    .andExpect(status().isOk());
            mvc.perform(headers(post(path+suffix),A,"runtime-a",TOKEN_A).queryParam("actorAgentId", B)
                    .contentType("application/json").content("{}")) .andExpect(status().isForbidden());
        }
        verify(leases).readLease(eq("tenant-a"),eq("client-a"),eq(A),eq("task-a"),eq("work-a"),eq("rsn-a"),any());
        verify(leases).startLease(eq("tenant-a"),eq("client-a"),eq(A),eq("task-a"),eq("work-a"),eq("rsn-a"),any());
        verify(leases).heartbeatLease(eq("tenant-a"),eq("client-a"),eq(A),eq("task-a"),eq("work-a"),eq("rsn-a"),any());
    }

    @Test void nonTargetMethodsPathsBrowsersAndAmbiguousHeadersAreDeniedBeforeController() throws Exception {
        for (String path : List.of("/resource", "/user/info", "/agent/tasks/task-a/work-items/work-a/reassignments",
                "/agent/tasks/task-a/context-pack/extra", "/agent/tasks/task-a/work-items/work-a/reassignments/r/lease/reassign")) {
            mvc.perform(headers(post(path),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
            mvc.perform(headers(get(path),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
        }
        mvc.perform(headers(post("/agent/tasks/task-a/context-pack"),A,"runtime-a",TOKEN_A)).andExpect(status().isForbidden());
        mvc.perform(requestA().header("Origin","https://browser.invalid")).andExpect(status().isForbidden());
        mvc.perform(requestA().header("Authorization","Bearer other")).andExpect(status().isUnauthorized());
        mvc.perform(requestA().header("X-Agent-Id", B)).andExpect(status().isUnauthorized());
        verifyNoInteractions(packs, leases);
    }

    @Test void revokeRotateDisconnectAndOldSocketCloseCannotAuthorizeOrKillNewBinding() throws Exception {
        persisted.get(A).setTokenHash("3".repeat(32));
        // A newly issued token cannot be combined with the old runtime/session binding.
        assertThrows(RuntimeException.class, () -> auth.authenticate(A, "runtime-a", "3".repeat(32)));
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        auth.bind("socket-a-new","tenant-a","client-a",A,"runtime-new","key-a","3".repeat(32), () -> true);
        auth.disconnect("socket-a");
        assertEquals(A,auth.authenticate(A,"runtime-new","3".repeat(32)).getName());
        assertThrows(RuntimeException.class,()->auth.authenticate(A,"runtime-a",TOKEN_A));
        auth.disconnect("socket-a-new");
        assertThrows(RuntimeException.class,()->auth.authenticate(A,"runtime-new","3".repeat(32)));
        assertEquals(B, auth.authenticate(B,"runtime-b",TOKEN_B).getName());
    }

    @Test void closedSocketRevokedKeySuspendedIdentityAndAccountLogoutEpochAreRechecked() throws Exception {
        keys.get("key-a").setApiKey("rotated-key");
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        keys.get("key-a").setApiKey("fixture-api-key-a");
        openA.set(false); mvc.perform(requestA()).andExpect(status().isUnauthorized()); openA.set(true);
        keys.get("key-a").setStatus(0); mvc.perform(requestA()).andExpect(status().isUnauthorized()); keys.get("key-a").setStatus(1);
        when(accounts.findUniqueByExactJiacn("tenant-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(1,"tenant-a",AccountState.ACTIVE,1)));
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        when(accounts.findUniqueByExactJiacn("tenant-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(1,"tenant-a",AccountState.ACTIVE,0)));
        persisted.get(A).setClientId("client-b"); mvc.perform(requestA()).andExpect(status().isUnauthorized());
        persisted.get(A).setClientId("client-a");
        persistedIdentitiesByBinding.get(1L)
                .setLifecycleStatus(AgentConstants.IDENTITY_STATUS_SUSPENDED).setSuspendedAt(3L);
        persistedBindings.get(1L).setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        mvc.perform(requestA()).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"AGENT_RUNTIME_UNAUTHENTICATED\"}"));
        verifyNoInteractions(packs,leases);
    }

    @Test void disabledCapabilityAndDirectControllerReassignmentCannotBypassTheNarrowLane() throws Exception {
        var disabledGate = new AgentTaskEventsGate(new AgentTaskEventsProperties(false, List.of()));
        var scopedAuth = new AgentRuntimeAuthenticationService(rows, context.getBean(AgentIdentityService.class),
                keys, accounts, disabledGate);
        var receipt = scopedAuth.bind("disabled", "tenant-a", "client-a", A, "runtime-a", "key-a", TOKEN_A, () -> true);
        assertFalse(receipt.contextPackEnabled());
        var gatedMvc = MockMvcBuilders.standaloneSetup(new AgentTaskContextPackController(packs, disabledGate))
                .addFilters(context.getBean(FilterChainProxy.class)).build();
        gatedMvc.perform(requestA()).andExpect(status().isServiceUnavailable());
        var controller = new AgentWorkItemReassignmentController(leases);
        assertThrows(RuntimeException.class, () -> controller.reassign("task-a", "work-a", "idempotency-a",
                new org.springframework.mock.web.MockHttpServletRequest(), auth.authenticate(A, "runtime-a", TOKEN_A)));
        mvc.perform(requestA().queryParam("actorAgentId", A)).andExpect(status().isBadRequest());
        verifyNoInteractions(packs, leases);
    }

    @Test void accountReplacementAndMissingBindingFailClosedWithoutDependingOnClientCookies() throws Exception {
        when(accounts.findUniqueByExactJiacn("tenant-a")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(999, "tenant-a", AccountState.ACTIVE, 0)));
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        when(accounts.findUniqueByExactJiacn("tenant-a")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(1, "tenant-a", AccountState.ACTIVE, 0)));
        persistedBindings.remove(1L);
        mvc.perform(requestA()).andExpect(status().isUnauthorized());
        verifyNoInteractions(packs, leases);
    }

    @Test void domainContextComesFromAuthenticatedScopeAndIsRestoredAfterRequest() throws Exception {
        var previous = new cn.jia.core.context.EsContext();
        previous.setJiacn("cookie-tenant"); previous.setClientId("cookie-client");
        cn.jia.core.context.EsContextHolder.setContext(previous);
        try {
            when(packs.generate("tenant-a", "client-a", "task-a", A, null)).thenAnswer(inv -> {
                assertEquals("tenant-a", cn.jia.core.context.EsContextHolder.getContext().getJiacn());
                assertEquals("client-a", cn.jia.core.context.EsContextHolder.getContext().getClientId());
                throw new cn.jia.agent.exception.AgentTaskContextPackException(
                        cn.jia.agent.exception.AgentTaskContextPackException.Reason.CONTEXT_UNAVAILABLE);
            });
            mvc.perform(requestA()).andExpect(status().isServiceUnavailable());
            assertSame(previous, cn.jia.core.context.EsContextHolder.getContext());
        } finally { cn.jia.core.context.EsContextHolder.clearContext(); }
    }

    @Test void selectorClaimsRuntimeCredentialsButNotTheEstablishedApiKeyWebSocketAgentHeader() throws Exception {
        var servletContext = new MockServletContext();
        assertFalse(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
                get("/ws/agent/channel").header("X-API-Key", "api-key")
                        .header("X-Agent-Id", A).buildRequest(servletContext)));
        assertFalse(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(
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

        mvc.perform(get("/agent/tasks/task-a/context-pack")
                        .header("Authorization", "AgentRuntimeMalformed")
                        .header("X-Agent-Id", A).header("X-Agent-Runtime-Id", "runtime-a"))
                .andExpect(status().isUnauthorized());
        mvc.perform(requestA().header("X-Agent-Runtime-Id", "runtime-duplicate"))
                .andExpect(status().isUnauthorized());
        mvc.perform(requestA().header("Authorization", "AgentRuntime " + TOKEN_B))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(packs, leases);
    }

    @Test void noMintedJwtAndBearerStillRequiresOriginalOauthPrincipalPath() throws Exception {
        var principal = auth.authenticate(A,"runtime-a",TOKEN_A);
        assertNull(principal.getCredentials()); assertFalse(principal.toString().contains(TOKEN_A));
        assertThrows(IllegalArgumentException.class,()->principal.setAuthenticated(true));
        assertFalse(AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane(get("/agent/tasks/task-a/context-pack")
                .header("Authorization","Bearer original-jwt").buildRequest(new MockServletContext())));
        // Without the original OAuth chain a bearer string cannot acquire this principal.
        mvc.perform(get("/agent/tasks/task-a/context-pack").header("Authorization","Bearer "+TOKEN_A))
                .andExpect(status().isUnauthorized());
        assertThrows(RuntimeException.class, () -> auth.bind("s","tenant-b","client-a",A,"r","key-a",TOKEN_A,()->true));
        verifyNoInteractions(packs,leases);
    }
}
