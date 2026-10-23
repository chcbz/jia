package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskArtifactContentDTO;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactContentService;
import cn.jia.agent.service.AgentTaskArtifactService;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskArtifactControllerTest {
    private static final String TASK = "task-1";
    private static final String ACTOR = "agent-1";
    private static final String ARTIFACT = "artifact-1";
    private static final byte[] BYTES = "exact artifact bytes".getBytes(StandardCharsets.UTF_8);

    private AgentTaskArtifactService artifactService;
    private AgentTaskArtifactContentService contentService;
    private AgentTaskArtifactController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        artifactService = mock(AgentTaskArtifactService.class);
        contentService = mock(AgentTaskArtifactContentService.class);
        controller = new AgentTaskArtifactController(artifactService, contentService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }





    @Test
    void anonymousNonJwtAndMalformedJwtClaimsAreRejectedBeforeBodyParsing() throws Exception {
        mvc.perform(post("/agent/tasks/%20bad%20/artifacts")
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        mvc.perform(post("/agent/tasks/%20bad%20/artifacts")
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "user", "n/a", List.of())))
                .andExpect(status().isUnauthorized());

        Jwt malformed = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "tenant-a").claim("client_id", 7)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        mvc.perform(post("/agent/tasks/%20bad%20/artifacts")
                        .contentType(MediaType.APPLICATION_JSON).content("{")
                        .principal(new JwtAuthenticationToken(malformed, List.of())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(artifactService, contentService);
    }



    @Test
    void disabledOrCorruptBackingServiceIsSafelyUnavailable() throws Exception {
        doThrow(new AgentTaskCollaborationException(
                Reason.INVALID_PERSISTED_STATE, "root=/private/storage"))
                .when(artifactService).publish(any(), any(), any(), any(), any(), any());

        mvc.perform(post("/agent/tasks/{taskId}/artifacts", TASK)
                        .queryParam("actorAgentId", ACTOR)
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody())
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/private/storage"))));
    }

    @Test
    void exactVersionDownloadReturnsExactBytesAndSafePrivateAttachmentHeaders()
            throws Exception {
        when(contentService.readContent(
                "0", "client-a", "tenant-a", TASK, ACTOR, ARTIFACT, 3))
                .thenReturn(new AgentTaskArtifactContentDTO(
                        ARTIFACT, 3, sha256(BYTES), (long) BYTES.length,
                        "application/octet-stream", BYTES));

        MvcResult result = mvc.perform(get(
                        "/agent/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 3)
                        .queryParam("actorAgentId", ACTOR)
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"artifact-v3.bin\""))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, Integer.toString(BYTES.length)))
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andReturn();

        assertArrayEquals(BYTES, result.getResponse().getContentAsByteArray());
        verify(contentService).readContent(
                "0", "client-a", "tenant-a", TASK, ACTOR, ARTIFACT, 3);
    }

    @Test
    void tenantAndClientMismatchDownloadsAreBothScopedAndNonomerating() throws Exception {
        when(contentService.readContent(
                "0", "client-a", "tenant-b", TASK, ACTOR, ARTIFACT, 1))
                .thenThrow(new AgentTaskCollaborationException(Reason.NOT_FOUND, "tenant secret"));
        when(contentService.readContent(
                "0", "client-b", "tenant-a", TASK, ACTOR, ARTIFACT, 1))
                .thenThrow(new AgentTaskCollaborationException(Reason.FORBIDDEN, "client secret"));

        MvcResult tenantMismatch = download(jwt("tenant-b", "client-a"));
        MvcResult clientMismatch = download(jwt("tenant-a", "client-b"));

        assertEquals(404, tenantMismatch.getResponse().getStatus());
        assertEquals(404, clientMismatch.getResponse().getStatus());
        assertEquals(tenantMismatch.getResponse().getContentAsString(),
                clientMismatch.getResponse().getContentAsString());
        assertEquals("private, no-store",
                tenantMismatch.getResponse().getHeader(HttpHeaders.CACHE_CONTROL));
        verify(contentService).readContent(
                "0", "client-a", "tenant-b", TASK, ACTOR, ARTIFACT, 1);
        verify(contentService).readContent(
                "0", "client-b", "tenant-a", TASK, ACTOR, ARTIFACT, 1);
    }

    @Test
    void contentReadAclMissingAndForbiddenHaveOnePublicNotFoundShape() throws Exception {
        when(contentService.readContent(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new AgentTaskCollaborationException(Reason.NOT_FOUND, "row absent"))
                .thenThrow(new AgentTaskCollaborationException(Reason.FORBIDDEN, "private owner"));

        MvcResult absent = download(jwt("tenant-a", "client-a"));
        MvcResult forbidden = download(jwt("tenant-a", "client-a"));

        assertEquals(404, absent.getResponse().getStatus());
        assertEquals(404, forbidden.getResponse().getStatus());
        assertEquals(absent.getResponse().getContentAsString(),
                forbidden.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertFalse(
                absent.getResponse().getContentAsString().contains("row absent"));
        org.junit.jupiter.api.Assertions.assertFalse(
                forbidden.getResponse().getContentAsString().contains("private owner"));
    }

    @Test
    void inconsistentDownloadMetadataOrBytesFails503WithoutPartialContent() throws Exception {
        when(contentService.readContent(any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(new AgentTaskArtifactContentDTO(
                        ARTIFACT, 1, "0".repeat(64), (long) BYTES.length,
                        "text/plain\r\nX-Secret: yes", BYTES));

        mvc.perform(get(
                        "/agent/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 1)
                        .queryParam("actorAgentId", ACTOR)
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("X-Secret"))));
    }

    @Test
    void invalidVersionAndActorAreRejectedBeforeContentAclCall() throws Exception {
        mvc.perform(get(
                        "/agent/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 0)
                        .queryParam("actorAgentId", ACTOR)
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, "01")
                        .queryParam("actorAgentId", ACTOR)
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 1)
                        .queryParam("actorAgentId", " outsider ")
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(
                        "/agent/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 1)
                        .queryParam("actorAgentId", ACTOR)
                        .queryParam("clientId", "client-b")
                        .principal(jwt("tenant-a", "client-a")))
                .andExpect(status().isBadRequest());
        verify(contentService, never()).readContent(any(), any(), any(), any(), any(), any(), anyInt());
    }

    private MvcResult download(JwtAuthenticationToken authentication) throws Exception {
        return mvc.perform(get(
                        "/agent/tasks/{taskId}/artifacts/{artifactId}/versions/{version}/content",
                        TASK, ARTIFACT, 1)
                        .queryParam("actorAgentId", ACTOR)
                        .principal(authentication))
                .andReturn();
    }

    private AgentTaskArtifactViewDTO publishedView() {
        AgentTaskArtifactViewDTO view = new AgentTaskArtifactViewDTO();
        view.setArtifactId(ARTIFACT);
        view.setTaskId(TASK);
        view.setWorkItemId("work-1");
        view.setProducerAgentId(ACTOR);
        view.setArtifactType("document");
        view.setTitle("Artifact");
        view.setContent(null);
        view.setStorageUri("cyf-artifact://internal-secret");
        view.setContentHash(sha256(BYTES));
        view.setContentByteLength((long) BYTES.length);
        view.setContentMimeType("application/octet-stream");
        view.setManagedStorage(true);
        view.setArtifactVersion(1);
        view.setVisibility("private");
        view.setMetadata(Map.of("secret", "metadata-secret"));
        view.setCreatedAt(1_800_000_000_000L);
        return view;
    }

    private String publishBody() {
        return "{\"artifactId\":\"" + ARTIFACT + "\","
                + "\"workItemId\":\"work-1\",\"artifactType\":\"document\","
                + "\"title\":\"Artifact\",\"contentBytes\":\""
                + Base64.getEncoder().encodeToString(BYTES) + "\","
                + "\"contentMimeType\":\"application/octet-stream\","
                + "\"artifactVersion\":1,\"expectedPreviousVersion\":0,"
                + "\"visibility\":\"private\","
                + "\"metadata\":{\"note\":\"safe\"}}";
    }

    private static String withField(String body, String field) {
        return body.substring(0, body.length() - 1) + "," + field + "}";
    }

    private JwtAuthenticationToken jwt(String tenant, String client) {
        return jwt(tenant, client, "0", "cyf");
    }

    private JwtAuthenticationToken jwt(String owner, String client, String tenantId, String domainId) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (tenantId != null) builder.claim("tenant_id", tenantId);
        if (domainId != null) builder.claim("data_domain_id", domainId);
        Jwt jwt = builder.build();
        return new JwtAuthenticationToken(jwt, List.of());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static MockHttpServletRequest generatedRequest(long byteCount) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                InputStream generated = new InputStream() {
                    private long remaining = byteCount;

                    @Override
                    public int read() {
                        if (remaining == 0) {
                            return -1;
                        }
                        remaining--;
                        return 'x';
                    }

                    @Override
                    public int read(byte[] target, int offset, int length) {
                        if (remaining == 0) {
                            return -1;
                        }
                        int count = (int) Math.min(remaining, length);
                        java.util.Arrays.fill(target, offset, offset + count, (byte) 'x');
                        remaining -= count;
                        return count;
                    }
                };
                return new DelegatingServletInputStream(generated);
            }
        };
        request.addParameter("actorAgentId", ACTOR);
        return request;
    }
}
