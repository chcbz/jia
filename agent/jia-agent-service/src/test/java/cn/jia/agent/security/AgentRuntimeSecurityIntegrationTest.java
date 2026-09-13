package cn.jia.agent.security;

import cn.jia.agent.api.AgentTaskContextPackController;
import cn.jia.agent.api.AgentWorkItemReassignmentController;
import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
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
    static final String TOKEN_A = "1".repeat(32), TOKEN_B = "2".repeat(32);
    AnnotationConfigWebApplicationContext context;
    AgentRuntimeAuthenticationService auth;
    AgentRuntimeDao rows;
    ApiKeyService keys;
    AccountSecurityService accounts;
    AgentTaskContextPackService packs;
    AgentWorkItemReassignmentService leases;
    MockMvc mvc;
    final Map<String, AgentRuntimeEntity> persisted = new HashMap<>();
    final AtomicBoolean openA = new AtomicBoolean(true);

    @Configuration(proxyBeanMethods = false) @EnableWebSecurity
    @Import(AgentRuntimeSecurityConfiguration.class)
    static class Wiring {
        @Bean AgentRuntimeDao rows() { return mock(AgentRuntimeDao.class); }
        @Bean AgentIdentityService identities() { return mock(AgentIdentityService.class); }
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
        rows = context.getBean(AgentRuntimeDao.class); keys = context.getBean(ApiKeyService.class);
        accounts = context.getBean(AccountSecurityService.class); packs = context.getBean(AgentTaskContextPackService.class);
        leases = context.getBean(AgentWorkItemReassignmentService.class);
        seed(A, "a", TOKEN_A); seed(B, "b", TOKEN_B);
        when(rows.findByAgentId(anyString())).thenAnswer(inv -> persisted.get(inv.getArgument(0)));
        var identities = context.getBean(AgentIdentityService.class);
        when(identities.requireActiveIdentityForBinding(anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> new AgentIdentityRegistryEntity().setCanonicalAgentId(inv.getArgument(4)));
        when(identities.requireActiveBinding(any(), isNull())).thenReturn(new AgentPersonaBindingEntity());
        auth.bind("socket-a", "tenant-a", "client-a", A, "runtime-a", "key-a", TOKEN_A, openA::get);
        auth.bind("socket-b", "tenant-b", "client-b", B, "runtime-b", "key-b", TOKEN_B, () -> true);
        mvc = MockMvcBuilders.standaloneSetup(new AgentTaskContextPackController(packs, context.getBean(AgentTaskEventsGate.class)),
                new AgentWorkItemReassignmentController(leases))
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { context.close(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    void seed(String agent, String suffix, String token) {
        var row = new AgentRuntimeEntity().setAgentId(agent).setOwnerJiacn("tenant-" + suffix)
                .setBindingId(suffix.equals("a") ? 1L : 2L).setTokenHash(token).setStatus("online");
        row.setTenantId("tenant-" + suffix); row.setClientId("client-" + suffix); persisted.put(agent, row);
        var key = new OauthApiKeyEntity().setId("key-" + suffix).setJiacn("tenant-" + suffix)
                .setClientId("client-" + suffix).setApiKey("fixture-api-key-" + suffix).setStatus(1);
        key.setTenantId("tenant-" + suffix);
        when(keys.get("key-" + suffix)).thenReturn(key);
        when(accounts.findUniqueByExactJiacn("tenant-" + suffix)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(suffix.equals("a") ? 1 : 2, "tenant-" + suffix, AccountState.ACTIVE, 0)));
    }
    MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request, String agent, String runtime, String token) {
        return request.header("Authorization", "AgentRuntime " + token)
                .header("X-Agent-Id", agent).header("X-Agent-Runtime-Id", runtime);
    }
    MockHttpServletRequestBuilder requestA() { return headers(get("/agent/tasks/task-a/context-pack"), A, "runtime-a", TOKEN_A); }

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
        when(context.getBean(AgentIdentityService.class).requireActiveIdentityForBinding("tenant-a","client-a","tenant-a",1L,A))
                .thenThrow(new IllegalArgumentException("secret source detail"));
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
        when(context.getBean(AgentIdentityService.class).requireActiveBinding(any(), isNull())).thenReturn(null);
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

    @Test void noMintedJwtAndBearerStillRequiresOriginalOauthPrincipalPath() throws Exception {
        var principal = auth.authenticate(A,"runtime-a",TOKEN_A);
        assertNull(principal.getCredentials()); assertFalse(principal.toString().contains(TOKEN_A));
        assertThrows(IllegalArgumentException.class,()->principal.setAuthenticated(true));
        assertFalse(AgentRuntimeAuthenticationFilter.selects(get("/agent/tasks/task-a/context-pack")
                .header("Authorization","Bearer original-jwt").buildRequest(new MockServletContext())));
        // Without the original OAuth chain a bearer string cannot acquire this principal.
        mvc.perform(get("/agent/tasks/task-a/context-pack").header("Authorization","Bearer "+TOKEN_A))
                .andExpect(status().isUnauthorized());
        assertThrows(RuntimeException.class, () -> auth.bind("s","tenant-b","client-a",A,"r","key-a",TOKEN_A,()->true));
        verifyNoInteractions(packs,leases);
    }
}
