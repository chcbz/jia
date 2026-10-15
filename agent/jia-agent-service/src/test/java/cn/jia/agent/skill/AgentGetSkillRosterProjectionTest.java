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
    }

    @Test
    void ownedGetUsesAuthoritativeDecimalVersionAndPreservesAgentMetadata() {
        wireProjection(true);
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
        ordered.verify(versions).enabled();
        ordered.verify(versions).requireOwned(ACTOR, AGENT_ID, null, false);
        verifyNoInteractions(skills);
    }

    @Test
    void actualGetRouteSerializesVersionAsDecimalString() throws Exception {
        wireProjection(true);
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
        wireProjection(true);
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
    void disabledMarketplaceKeepsRealOwnershipButNeverReadsPersistentVersion() {
        wireProjection(false);
        when(agents.get(AGENT_ID)).thenReturn(row());

        var result = getRow();

        assertNull(result.getVersion());
        assertEquals(Boolean.TRUE, result.getBoundToMe());
        assertEquals(Boolean.TRUE, result.getCanOperate());
        verify(versions).enabled();
        verifyNoMoreInteractions(versions);
        verifyNoInteractions(skills);
    }

    @Test
    void originalServiceDenialPrecedesAnySkillProjection() {
        wireProjection(true);
        var denial = new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Outside scope");
        when(agents.get(AGENT_ID)).thenThrow(denial);

        assertSame(denial, assertThrows(AgentBizException.class, () -> controller.get(AGENT_ID)));
        verify(agents).get(AGENT_ID);
        verifyNoMoreInteractions(agents);
        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void missingAuthenticationClearsStaleSkillAuthorityAndDoesNotLoadVersion() {
        wireProjection(true);
        SecurityContextHolder.clearContext();
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void nonJwtAuthenticationCannotUseLegacyOwnerFallback() {
        wireProjection(true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("legacy-user", "n/a", List.of()));
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    @Test
    void unprovenOwnerCannotRetainStaleVersionOrPermissions() {
        wireProjection(true);
        when(owners.requireOwner(ACTOR)).thenThrow(new IllegalStateException("Owner unproven"));
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(agentProvider, versions, skills);
    }

    @Test
    void revokedOwnershipCannotRetainStaleVersionOrPermissions() {
        wireProjection(true);
        when(agents.requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID))
                .thenThrow(new AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "Revoked"));
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(versions, skills);
    }

    @Test
    void mismatchedTrustedAgentCannotExposeAnotherAgentsVersion() {
        wireProjection(true);
        var other = row();
        other.setAgentId("other-agent");
        when(agents.requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID)).thenReturn(other);
        when(agents.get(AGENT_ID)).thenReturn(row());

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(versions, skills);
    }

    @Test
    void systemAgentIsNeverMadePurchasable() {
        wireProjection(true);
        var system = row();
        system.setSystemAgent(true);
        when(agents.get(AGENT_ID)).thenReturn(system);

        assertNoSkillAuthority(getRow());

        verifyNoInteractions(agentProvider, versions, skills);
    }

    @Test
    void unavailablePersistentVersionDoesNotFallBackToStaleOrHeartbeatToken() {
        wireProjection(true);
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
        wireProjection(true);
        when(agents.get(AGENT_ID)).thenReturn(null);

        var result = assertInstanceOf(JsonResult.class, controller.get(AGENT_ID));

        assertNull(result.getData());
        verifyNoInteractions(owners, agentProvider, versions, skills);
    }

    private void wireProjection(boolean enabled) {
        var jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .subject("login-sub").claim("client_id", "Client-A").claim("jiacn", "Owner-A")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        when(owners.requireOwner(ACTOR)).thenReturn("Owner-A");
        when(agentProvider.getObject()).thenReturn(agents);
        when(agents.requireApiKeyOwnedAgent("Client-A", "Owner-A", AGENT_ID)).thenReturn(row());
        when(versions.enabled()).thenReturn(enabled);
        if (enabled) when(versions.requireOwned(ACTOR, AGENT_ID, null, false)).thenReturn(VERSION);
        controller.setSkillRoster(new SkillRosterProjection(owners, agentProvider, versions, skills));
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
