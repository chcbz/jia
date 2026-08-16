package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskWorkspaceControllerTest extends BaseMockTest {
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock AgentTaskWorkspaceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskWorkspaceController(service)).build();
    }

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void jwtClaimsAreSoleScopeAuthorityAndSuccessIsRawNoStoreJson() throws Exception {
        EsContext poisoned = new EsContext();
        poisoned.setJiacn("cookie-tenant");
        poisoned.setClientId("cookie-client");
        EsContextHolder.setContext(poisoned);
        JwtAuthenticationToken authentication = authenticate("tenant-a", "client-a");
        when(service.snapshot("tenant-a", "client-a", TASK, ACTOR))
                .thenReturn(snapshot("9007199254740993"));

        mvc.perform(get("/agent/tasks/{taskId}/workspace", TASK)
                        .queryParam("actorAgentId", ACTOR).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.currentVersion").value("9007199254740993"))
                .andExpect(jsonPath("$.conversationId").value(nullValue()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(service).snapshot("tenant-a", "client-a", TASK, ACTOR);
    }

    @Test
    void missingAuthenticationAndMissingStringClaimsAre401And403WithNoStore() throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/workspace", TASK)
                        .queryParam("actorAgentId", ACTOR))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", "tenant-a")
                .claim("client_id", 123)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        JwtAuthenticationToken badClaims = new JwtAuthenticationToken(jwt, List.of());
        mvc.perform(get("/agent/tasks/{taskId}/workspace", TASK)
                        .queryParam("actorAgentId", ACTOR).principal(badClaims))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
    }

    @Test
    void malformedMissingAndDeniedRequestsUseFrozenStatusesAndNoStore() throws Exception {
        JwtAuthenticationToken authentication = authenticate("tenant-a", "client-a");
        mvc.perform(get("/agent/tasks/{taskId}/workspace", TASK).principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        mvc.perform(get("/agent/tasks/{taskId}/workspace", " padded ")
                        .queryParam("actorAgentId", ACTOR).principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        mvc.perform(get("/agent/tasks/{taskId}/workspace", TASK)
                        .queryParam("actorAgentId", "\u00a0" + ACTOR).principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        when(service.snapshot("tenant-a", "client-a", TASK, ACTOR)).thenThrow(
                new AgentTaskWorkspaceException(
                        AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN));
        mvc.perform(get("/agent/tasks/{taskId}/workspace", TASK)
                        .queryParam("actorAgentId", ACTOR).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_WORKSPACE_NOT_FOUND"));
    }

    @Test
    void serializedResponseAboveFourMiBFails503InsteadOfTruncating() throws Exception {
        JwtAuthenticationToken authentication = authenticate("tenant-a", "client-a");
        AgentTaskWorkspaceDTO snapshot = snapshot("0");
        snapshot.getTask().setRequiredAbilities("x".repeat(
                AgentTaskWorkspaceController.MAX_SERIALIZED_BYTES));
        when(service.snapshot("tenant-a", "client-a", TASK, ACTOR)).thenReturn(snapshot);

        mvc.perform(get("/agent/tasks/{taskId}/workspace", TASK)
                        .queryParam("actorAgentId", ACTOR).principal(authentication))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("TASK_WORKSPACE_UNAVAILABLE"));
    }

    @Test
    void earliestFilterAddsNoStoreBeforeDownstreamSecurityResponse() throws Exception {
        AgentTaskWorkspaceCacheControlFilter filter = new AgentTaskWorkspaceCacheControlFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/agent/tasks/task-1/workspace");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        org.junit.jupiter.api.Assertions.assertEquals(401, response.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("private, no-store",
                response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    private static JwtAuthenticationToken authenticate(String jiacn, String clientId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", jiacn)
                .claim("client_id", clientId)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }

    private static AgentTaskWorkspaceDTO snapshot(String currentVersion) {
        AgentTaskWorkspaceDTO dto = new AgentTaskWorkspaceDTO();
        AgentTaskWorkspaceDTO.Task task = new AgentTaskWorkspaceDTO.Task();
        task.setTaskId(TASK);
        task.setStatus("running");
        task.setVersion("0");
        dto.setTask(task);
        dto.setMembers(List.of());
        dto.setWorkItems(List.of());
        dto.setOpenRequests(List.of());
        dto.setRecentArtifacts(List.of());
        dto.setRecentEvents(List.of());
        dto.setConversationId(null);
        dto.setCurrentVersion(currentVersion);
        return dto;
    }
}
