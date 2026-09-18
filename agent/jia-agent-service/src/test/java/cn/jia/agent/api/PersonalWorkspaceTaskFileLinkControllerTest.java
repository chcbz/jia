package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalWorkspaceTaskFileLinkControllerTest extends BaseMockTest {
    @Mock PersonalWorkspaceTaskLinkService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new PersonalWorkspaceTaskFileLinkController(service)).build();
    }

    @Test
    void jwtOwnerAndClientAreTheOnlyScopeAndNoActorAgentIsRequired() throws Exception {
        var scope = new PersonalWorkspaceTaskLinkService.Scope("0", "browser", "owner-a");
        when(service.list(scope, "task-a", null))
                .thenReturn(new PersonalWorkspaceTaskLinkService.LinkListView(List.of(), null));

        mvc.perform(get("/agent/tasks/task-a/file-links").principal(jwt("owner-a", "browser")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verify(service).list(scope, "task-a", null);
    }

    @Test
    void authenticationPrecedesMissingIdempotencyKeyAndBodyOwnerIsRejected() throws Exception {
        mvc.perform(post("/agent/tasks/task-a/file-links")
                        .contentType("application/json")
                        .content("{\"fileId\":\"file-a\",\"version\":1,\"role\":\"INPUT\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verifyNoInteractions(service);

        mvc.perform(post("/agent/tasks/task-a/file-links")
                        .principal(jwt("owner-a", "browser"))
                        .header("Idempotency-Key", "key-a")
                        .contentType("application/json")
                        .content("{\"fileId\":\"file-a\",\"version\":1,\"role\":\"INPUT\",\"owner\":\"owner-b\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void noLeakNotFoundAndOutputProtectionUseStableResponses() throws Exception {
        var scope = new PersonalWorkspaceTaskLinkService.Scope("0", "browser", "owner-a");
        when(service.list(scope, "foreign-task", null)).thenThrow(
                new PersonalWorkspaceTaskLinkService.Failure(
                        PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND));
        mvc.perform(get("/agent/tasks/foreign-task/file-links")
                        .principal(jwt("owner-a", "browser")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_FILE_LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Task file link resource is unavailable"));

        when(service.create(scope, "task-a", new PersonalWorkspaceTaskLinkService.CreateCommand(
                "file-a", 1, "OUTPUT", "key-output"))).thenThrow(
                new PersonalWorkspaceTaskLinkService.Failure(
                        PersonalWorkspaceTaskLinkService.Reason.OUTPUT_MANAGED_BY_EXECUTION));
        mvc.perform(post("/agent/tasks/task-a/file-links")
                        .principal(jwt("owner-a", "browser"))
                        .header("Idempotency-Key", "key-output")
                        .contentType("application/json")
                        .content("{\"fileId\":\"file-a\",\"version\":1,\"role\":\"OUTPUT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUTPUT_LINK_MANAGED_BY_EXECUTION"));
    }

    @Test
    void deleteForwardsConditionalAndIdempotencyHeadersWithoutOwnerInput() throws Exception {
        var scope = new PersonalWorkspaceTaskLinkService.Scope("0", "browser", "owner-a");
        var link = new PersonalWorkspaceTaskLinkService.LinkView(
                "rel-a", "task-a", "file-a", 1, "INPUT", "DETACHED", 2, 1);
        when(service.detach(scope, "task-a", "rel-a", "\"rel-a:1\"", "delete-key"))
                .thenReturn(new PersonalWorkspaceTaskLinkService.DetachView(link, true));

        mvc.perform(delete("/agent/tasks/task-a/file-links/rel-a")
                        .principal(jwt("owner-a", "browser"))
                        .header(HttpHeaders.IF_MATCH, "\"rel-a:1\"")
                        .header("Idempotency-Key", "delete-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"rel-a:2\""))
                .andExpect(jsonPath("$.executionSnapshotsPreserved").value(true));
        verify(service).detach(scope, "task-a", "rel-a", "\"rel-a:1\"", "delete-key");
        verify(service, never()).create(scope, "task-a", null);
    }

    private static JwtAuthenticationToken jwt(String owner, String client) {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
