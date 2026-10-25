package cn.jia.agent.skill;

import cn.jia.agent.api.AgentController;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.entity.AgentRosterSearchDTO;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Single-Agent GET must use the same persistent ownership/version projection as the roster. */
class AgentGetSkillRosterProjectionTest {
    private static final String AGENT_ID = "jyt-client-wuyong";
    private static final HostingRentHttp.Actor ACTOR =
            new HostingRentHttp.Actor("login-sub", "0", "Client-A", "Owner-A");
    private static final long VERSION = 9007199254740993L;
    private final AgentService agents = mock(AgentService.class);
    private final HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentService> agentProvider = mock(ObjectProvider.class);
    private final SkillAgentVersions versions = mock(SkillAgentVersions.class);
    private final SkillMarketplaceService skills = mock(SkillMarketplaceService.class);
    private final AgentController controller =
            new AgentController(agents, mock(AbilityEvaluationService.class));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        EsContextHolder.clearContext();
    }

    @Test
    void ownedGetUsesAuthoritativeDecimalVersionAndPreservesAgentMetadata() {
        wireProjection();
        var row = row();
        when(agents.get(AGENT_ID)).thenReturn(row);

        var result = getRow();

        assertSame(row, result);
        assertEquals("9007199254740993", result.getVersion());
        assertEquals(Boolean.TRUE, result.getBoundToMe());
        assertEquals(Boolean.TRUE, result.getCanOperate());
        assertEquals("Owner-A", result.getOwnerJiacn());
        assertEquals("online", result.getStatus());
        assertEquals("吴用", result.getName());
        var ordered = inOrder(agents, versions);
        ordered.verify(agents).get(AGENT_ID);
        ordered.verify(agents).requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID);
        ordered.verify(versions).requireOwned(ACTOR, AGENT_ID, null, false);
        verifyNoInteractions(skills);
    }

    @Test
    void actualGetRouteSerializesVersionAsDecimalString() throws Exception {
        wireProjection();
        when(agents.get(AGENT_ID)).thenReturn(row());

        var response = MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/agent/" + AGENT_ID)).andReturn().getResponse();

        assertEquals(200, response.getStatus());
        var data = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.getContentAsString()).path("data");
        assertTrue(data.path("version").isString());
        assertEquals("9007199254740993", data.path("version").asString());
        assertTrue(data.path("boundToMe").asBoolean());
        assertTrue(data.path("canOperate").asBoolean());
    }

    @Test
    void getAndRosterUseTheSameAuthoritativeVersionAndOwnershipProjection() {
        wireProjection();
        var single = row();
        var listed = row();
        when(agents.get(AGENT_ID)).thenReturn(single);
        when(agents.listRoster(null, null, 1, 50)).thenReturn(new PageInfo<>(List.of(listed)));

        getRow();
        controller.roster(new cn.jia.core.entity.JsonRequest<>(1, "test-request", new AgentRosterSearchDTO()));

        assertEquals(single.getVersion(), listed.getVersion());
        assertEquals(single.getBoundToMe(), listed.getBoundToMe());
        assertEquals(single.getCanOperate(), listed.getCanOperate());
        verify(versions, times(2)).requireOwned(ACTOR, AGENT_ID, null, false);
    }

    @Test
    void tenantOneOwnedGetAndRosterPreserveChatAuthorityWithoutEnteringLegacySkillPath() {
        wireTenantProjection("1", "Client-A", "Owner-A");
        var single = row();
        var listed = row();
        when(agents.get(AGENT_ID)).thenReturn(single);
        when(agents.listRoster(null, null, 1, 50)).thenReturn(new PageInfo<>(List.of(listed)));
        when(agents.requireRosterOwnedAgent("1", "Client-A", "Owner-A", AGENT_ID))
                .thenReturn(row());

        var getResult = getRow();
        controller.roster(new cn.jia.core.entity.JsonRequest<>(
                1, "test-request", new AgentRosterSearchDTO()));

        assertEquals(Boolean.TRUE, getResult.getBoundToMe());
        assertEquals(Boolean.TRUE, getResult.getCanOperate());
        assertNull(getResult.getVersion());
        assertEquals(Boolean.TRUE, listed.getBoundToMe());
        assertEquals(Boolean.TRUE, listed.getCanOperate());
        assertNull(listed.getVersion());
        verify(agents, times(2)).requireRosterOwnedAgent(
                "1", "Client-A", "Owner-A", AGENT_ID);
        verify(agents, never()).requireApiKeyOwnedAgent(any(), any(), any());
        verifyNoInteractions(owners, versions, skills);
    }

    @Test
    void tenantOneForeignOwnerProjectionCannotReuseAuthoritativeAgentId() {
        wireTenantProjection("1", "Client-A", "Owner-A");
        when(agents.get(AGENT_ID)).thenReturn(row());
        var foreign = row();
        foreign.setOwnerJiacn("Owner-B");
        when(agents.requireRosterOwnedAgent("1", "Client-A", "Owner-A", AGENT_ID))
                .thenReturn(foreign);

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(owners, versions, skills);
    }

    @Test
    void tenantOneServiceDenialForForeignScopeCannotPreserveDtoFlags() {
        wireTenantProjection("1", "Client-A", "Owner-A");
        when(agents.get(AGENT_ID)).thenReturn(row());
        when(agents.requireRosterOwnedAgent("1", "Client-A", "Owner-A", AGENT_ID))
                .thenThrow(new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Foreign scope"));

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(owners, versions, skills);
    }

    @Test
    void jwtAndRequestContextMustMatchTenantClientAndOwnerByteExact() {
        var projection = new SkillRosterProjection(owners, agentProvider, versions, skills);
        var authentication = jwt("1", "Client-A", "Owner-A");
        for (String[] context : List.of(
                new String[]{"2", "Client-A", "Owner-A"},
                new String[]{"1", "Client-B", "Owner-A"},
                new String[]{"1", "Client-A", "Owner-B"})) {
            setContext(context[0], context[1], context[2]);
            var projected = row();

            projection.project(authentication, List.of(projected));

            assertNoSkillAuthority(projected);
        }
        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void originalServiceDenialPrecedesAnySkillProjection() {
        wireProjection();
        var denial = new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Outside scope");
        when(agents.get(AGENT_ID)).thenThrow(denial);

        assertSame(denial, assertThrows(AgentBizException.class, () -> controller.get(AGENT_ID)));
        verify(agents).get(AGENT_ID);
        verifyNoMoreInteractions(agents);
        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void missingAuthenticationClearsStaleSkillAuthorityAndDoesNotLoadVersion() {
        wireProjection();
        SecurityContextHolder.clearContext();
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void nonJwtAuthenticationCannotUseLegacyOwnerFallback() {
        wireProjection();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("legacy-user", "n/a", List.of()));
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void unprovenOwnerCannotRetainStaleVersionOrPermissions() {
        wireProjection();
        when(owners.requireOwner(ACTOR)).thenThrow(new IllegalStateException("Owner unproven"));
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(agentProvider, versions, skills);
    }

    @Test
    void revokedOwnershipCannotRetainStaleVersionOrPermissions() {
        wireProjection();
        when(agents.requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID))
                .thenThrow(new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Revoked"));
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(versions, skills);
    }

    @Test
    void mismatchedTrustedAgentCannotExposeAnotherAgentsVersion() {
        wireProjection();
        var other = row();
        other.setAgentId("other-agent");
        when(agents.requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID)).thenReturn(other);
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(versions, skills);
    }

    @Test
    void systemAgentIsNeverMadePurchasable() {
        wireProjection();
        var system = row();
        system.setSystemAgent(true);
        when(agents.get(AGENT_ID)).thenReturn(system);

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(agentProvider, versions, skills);
    }

    @Test
    void unavailablePersistentVersionDoesNotFallBackToStaleOrHeartbeatToken() {
        wireProjection();
        when(versions.requireOwned(ACTOR, AGENT_ID, null, false))
                .thenThrow(new IllegalStateException("Version unavailable"));
        when(agents.get(AGENT_ID)).thenReturn(row());

        var result = getRow();

        assertNull(result.getVersion());
        assertEquals(Boolean.TRUE, result.getBoundToMe());
        assertEquals(Boolean.TRUE, result.getCanOperate());
        assertEquals(Long.valueOf(123456789L), result.getLastSeenAt());
    }

    @Test
    void missingOptionalProjectionPreservesOriginalServiceResult() {
        var row = row();
        when(agents.get(AGENT_ID)).thenReturn(row);

        assertSame(row, getRow());
        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void nullServiceResultRetainsOriginalResponseWithoutProjectingNull() {
        wireProjection();
        when(agents.get(AGENT_ID)).thenReturn(null);

        var result = assertInstanceOf(JsonResult.class, controller.get(AGENT_ID));

        assertNull(result.getData());
        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    private void wireProjection() {
        setAuthenticationAndContext("0", "Client-A", "Owner-A");
        when(owners.requireOwner(ACTOR)).thenReturn("Owner-A");
        when(agentProvider.getObject()).thenReturn(agents);
        when(agents.requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID)).thenReturn(row());
        when(versions.requireOwned(ACTOR, AGENT_ID, null, false)).thenReturn(VERSION);
        controller.setSkillRoster(new SkillRosterProjection(owners, agentProvider, versions, skills));
    }

    private void wireTenantProjection(String tenantId, String clientId, String ownerJiacn) {
        setAuthenticationAndContext(tenantId, clientId, ownerJiacn);
        when(agentProvider.getObject()).thenReturn(agents);
        controller.setSkillRoster(new SkillRosterProjection(owners, agentProvider, versions, skills));
    }

    private static void setAuthenticationAndContext(
            String tenantId, String clientId, String ownerJiacn) {
        SecurityContextHolder.getContext().setAuthentication(jwt(tenantId, clientId, ownerJiacn));
        setContext(tenantId, clientId, ownerJiacn);
    }

    private static void setContext(String tenantId, String clientId, String ownerJiacn) {
        EsContext context = new EsContext();
        context.setTenantId(tenantId);
        context.setClientId(clientId);
        context.setJiacn(ownerJiacn);
        EsContextHolder.setContext(context);
    }

    private static JwtAuthenticationToken jwt(
            String tenantId, String clientId, String ownerJiacn) {
        Jwt.Builder jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject("login-sub").claim("client_id", clientId).claim("jiacn", ownerJiacn);
        if (!"0".equals(tenantId)) {
            jwt.claim("tenant_id", tenantId).claim("tenant_claim_version", "1");
        }
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }

    private AgentRuntimeDTO getRow() {
        var result = assertInstanceOf(JsonResult.class, controller.get(AGENT_ID));
        return assertInstanceOf(AgentRuntimeDTO.class, result.getData());
    }

    private static AgentRuntimeDTO row() {
        var row = new AgentRuntimeDTO();
        row.setAgentId(AGENT_ID);
        row.setName("吴用");
        row.setOwnerJiacn("Owner-A");
        row.setStatus("online");
        row.setSystemAgent(false);
        row.setVersion("stale");
        row.setBoundToMe(true);
        row.setCanOperate(true);
        row.setLastSeenAt(123456789L);
        return row;
    }

    private static void assertNoSkillAuthority(AgentRuntimeDTO row) {
        assertNull(row.getVersion());
        assertEquals(Boolean.FALSE, row.getBoundToMe());
        assertEquals(Boolean.FALSE, row.getCanOperate());
    }
}
