package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskDeliverableControllerTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String ARTIFACT = "artifact-1";
    private static final byte[] BYTES = "exact deliverable bytes".getBytes(StandardCharsets.UTF_8);

    private AgentTaskArtifactService artifactService;
    private AgentTaskArtifactContentService contentService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        artifactService = mock(AgentTaskArtifactService.class);
        contentService = mock(AgentTaskArtifactContentService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskDeliverableController(artifactService, contentService)).build();
    }

    @Test
    void listBindsTenantClientAndActorOnlyFromValidatedJwtAndFiltersResponse() throws Exception {
        when(artifactService.listForTaskOwner(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(view()));

        mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                        .queryParam("workItemId", "work-1")
                        .queryParam("limit", "25")
                        .principal(jwt(TENANT, CLIENT, OTHER)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.items[0].artifactId").value(ARTIFACT))
                .andExpect(jsonPath("$.items[0].artifactVersion").value(3))
                .andExpect(jsonPath("$.items[0].contentHash").value(sha256(BYTES)))
                .andExpect(jsonPath("$.items[0].contentByteLength").value(BYTES.length))
                .andExpect(jsonPath("$.items[0].storageUri").doesNotExist())
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].metadata").doesNotExist())
                .andExpect(jsonPath("$.items[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.items[0].clientId").doesNotExist());

        ArgumentCaptor<AgentTaskArtifactQueryDTO> query =
                ArgumentCaptor.forClass(AgentTaskArtifactQueryDTO.class);
        verify(artifactService).listForTaskOwner(eq(TENANT), eq(CLIENT), eq(TASK), query.capture());
        assertEquals("work-1", query.getValue().getWorkItemId());
        assertEquals(25, query.getValue().getLimit());
    }

    @Test
    void listRejectsActorScopeCursorAndDuplicateQueryInjectionBeforeService() throws Exception {
        JwtAuthenticationToken auth = jwt(TENANT, CLIENT, ACTOR);
        for (String uri : List.of(
                "/agent/tasks/task-1/deliverables?actorAgentId=" + OTHER,
                "/agent/tasks/task-1/deliverables?tenantId=tenant-b",
                "/agent/tasks/task-1/deliverables?clientId=client-b",
                "/agent/tasks/task-1/deliverables?cursor=opaque",
                "/agent/tasks/task-1/deliverables?workItemId=",
                "/agent/tasks/task-1/deliverables?limit=0",
                "/agent/tasks/task-1/deliverables?limit=01",
                "/agent/tasks/task-1/deliverables?limit=101",
                "/agent/tasks/task-1/deliverables?limit=1&limit=2")) {
            mvc.perform(get(uri).principal(auth))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(artifactService, contentService);
    }

    @Test
    void anonymousNonJwtMalformedClaimsAndNameMismatchFailBeforeService() throws Exception {
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "user", "n/a", List.of())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables")
                        .principal(jwtClaims(TENANT, 7, ACTOR, ACTOR)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/agent/tasks/%20bad%20/deliverables")
                        .principal(jwtClaims(TENANT, CLIENT, ACTOR, OTHER)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(artifactService, contentService);
    }

    @Test
    void ownerScopeDoesNotNeedPersonaBindingAndTaskNotFoundRemainsOpaque() throws Exception {
        when(artifactService.listForTaskOwner(eq(TENANT), eq(CLIENT), eq(TASK), any()))
                .thenReturn(List.of(view()));
        mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                        .principal(jwt(TENANT, CLIENT, OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].artifactId").value(ARTIFACT));

        when(artifactService.listForTaskOwner(eq("tenant-b"), eq(CLIENT), eq(TASK), any()))
                .thenThrow(new cn.jia.agent.exception.AgentTaskCollaborationException(
                        cn.jia.agent.exception.AgentTaskCollaborationException.Reason.NOT_FOUND,
                        "outside scope"));
        MvcResult foreign = list(jwt("tenant-b", CLIENT, OTHER));
        assertEquals(404, foreign.getResponse().getStatus());
        assertFalse(foreign.getResponse().getContentAsString().contains("outside scope"));
    }

    @Test
    void contentDownloadUsesTaskOwnerScopeAndReturnsExactSafeBytes() throws Exception {
        when(contentService.readContentForTaskOwner(TENANT, CLIENT, TASK, ARTIFACT, 3))
                .thenReturn(new AgentTaskArtifactContentDTO(ARTIFACT, 3, sha256(BYTES),
                        (long) BYTES.length, "application/octet-stream", BYTES));

        MvcResult result = mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 3)
                        .principal(jwt(TENANT, CLIENT, OTHER)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"deliverable-v3.bin\""))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andReturn();

        assertArrayEquals(BYTES, result.getResponse().getContentAsByteArray());
        verify(contentService).readContentForTaskOwner(TENANT, CLIENT, TASK, ARTIFACT, 3);
    }

    @Test
    void contentDownloadRejectsQueryAndInvalidVersionsBeforeContentService() throws Exception {
        JwtAuthenticationToken auth = jwt(TENANT, CLIENT, ACTOR);
        for (String uri : List.of(
                "/agent/tasks/task-1/deliverables/artifact-1/versions/0/content",
                "/agent/tasks/task-1/deliverables/artifact-1/versions/01/content",
                "/agent/tasks/task-1/deliverables/artifact-1/versions/1/content?actorAgentId=" + OTHER,
                "/agent/tasks/task-1/deliverables/artifact-1/versions/1/content?workItemId=work-1")) {
            mvc.perform(get(uri).principal(auth)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(contentService);
    }

    @Test
    void corruptArtifactMetadataAndBytesFailClosedWithoutLeakingFields() throws Exception {
        AgentTaskArtifactViewDTO corrupt = view();
        corrupt.setContentMimeType("text/plain\r\nX-Secret: yes");
        when(artifactService.listForTaskOwner(anyString(), anyString(), anyString(), any()))
                .thenReturn(List.of(corrupt));
        mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                        .principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("X-Secret"))));

        when(contentService.readContentForTaskOwner(anyString(), anyString(), anyString(),
                anyString(), anyInt()))
                .thenReturn(new AgentTaskArtifactContentDTO(ARTIFACT, 1, "0".repeat(64),
                        (long) BYTES.length, "text/plain", BYTES));
        mvc.perform(get(
                        "/agent/tasks/{taskId}/deliverables/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 1).principal(jwt(TENANT, CLIENT, ACTOR)))
                .andExpect(status().isServiceUnavailable());
    }

    private MvcResult list(JwtAuthenticationToken authentication) throws Exception {
        return mvc.perform(get("/agent/tasks/{taskId}/deliverables", TASK)
                .principal(authentication)).andReturn();
    }

    private static AgentTaskArtifactViewDTO view() {
        AgentTaskArtifactViewDTO view = new AgentTaskArtifactViewDTO();
        view.setArtifactId(ARTIFACT);
        view.setTaskId(TASK);
        view.setWorkItemId("work-1");
        view.setProducerAgentId(ACTOR);
        view.setArtifactType("document");
        view.setTitle("交付报告");
        view.setContent("legacy bytes never leave catalog");
        view.setStorageUri("cyf-artifact://internal-secret");
        view.setContentHash(sha256(BYTES));
        view.setContentByteLength((long) BYTES.length);
        view.setContentMimeType("application/octet-stream");
        view.setManagedStorage(true);
        view.setArtifactVersion(3);
        view.setVisibility("task_members");
        view.setMetadata(java.util.Map.of("secret", "must-not-leak"));
        view.setCreatedAt(123L);
        return view;
    }

    private static JwtAuthenticationToken jwt(String tenant, String client, String actor) {
        return jwtClaims(tenant, client, actor, actor);
    }

    private static JwtAuthenticationToken jwtClaims(
            String tenant, Object client, String subject, String authenticationName) {
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("jiacn", tenant)
                .claim("client_id", client)
                .claim("sub", subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of()) {
            @Override
            public String getName() {
                return authenticationName;
            }
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
