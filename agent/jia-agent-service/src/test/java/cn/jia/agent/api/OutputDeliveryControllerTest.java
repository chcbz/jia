package cn.jia.agent.api;

import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.dto.OutputCapabilitiesDTO;
import cn.jia.agent.output.dto.OutputDownloadDTO;
import cn.jia.agent.output.dto.OutputDetailDTO;
import cn.jia.agent.output.dto.OutputPageDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.output.dto.OutputSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OutputDeliveryControllerTest {
    private static final byte[] BYTES = "hello world!\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private OutputDeliveryService service;
    private OutputDeliveryController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OutputDeliveryService.class);
        controller = new OutputDeliveryController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void capabilitiesAndTaskListUseExactUserJwtScope() throws Exception {
        when(service.capabilities()).thenReturn(new OutputCapabilitiesDTO(
                true, true, true, false, "52428800", "209715200", 100,
                List.of("text/plain")));
        when(service.list("owner", "client", "owner", "TASK", "task-1", null, 20))
                .thenReturn(new OutputPageDTO(List.of(summary()), null, "123"));

        mvc.perform(get("/agent/output-capabilities").principal(jwt("owner", "client")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.outputReadV1").value(true))
                .andExpect(jsonPath("$.data.taskDeliveryHttpV1").value(false));
        mvc.perform(get("/agent/tasks/task-1/artifacts?limit=20")
                        .principal(jwt("owner", "client")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("safe title"));

        verify(service).list("owner", "client", "owner", "TASK", "task-1", null, 20);
    }

    @Test
    void missingOrMalformedUserClaimsFailBeforeServiceAccess() throws Exception {
        mvc.perform(get("/agent/tasks/task-1/artifacts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OUTPUT_AUTH_UNAUTHORIZED"));
        mvc.perform(get("/agent/tasks/task-1/artifacts")
                        .principal(jwt(Map.of("jiacn", "owner", "client_id", 7))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OUTPUT_AUTH_FORBIDDEN"));

        verify(service, never()).list(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void strictTaskPublicationRejectsDuplicateAndUnknownFields() throws Exception {
        String common = "\"expectedPreviousVersion\":\"0\",\"title\":\"safe\","
                + "\"artifactType\":\"document\",\"content\":\"body\","
                + "\"artifactId\":\"artifact-1\",\"artifactVersion\":\"1\"";
        mvc.perform(post("/agent/tasks/task-1/artifacts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + "a".repeat(43))
                        .header("Idempotency-Key", "publish-http-key-01")
                        .contentType("application/json")
                        .content("{\"runId\":\"run-1\",\"runId\":\"run-2\"," + common + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"));
        mvc.perform(post("/agent/tasks/task-1/artifacts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + "a".repeat(43))
                        .header("Idempotency-Key", "publish-http-key-02")
                        .contentType("application/json")
                        .content("{\"runId\":\"run-1\"," + common + ",\"bucket\":\"secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.retryable").value(false));

        verify(service, never()).publish(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void publishVersionsAndDetailRoutesUseFrozenContract() throws Exception {
        OutputSummaryDTO summary=summary();
        when(service.publish(anyString(),anyString(),anyString(),anyString(),any()))
                .thenReturn(summary);
        when(service.listVersions("owner","client","owner","TASK","task-1","artifact-1",null,10))
                .thenReturn(new OutputPageDTO(List.of(summary),null,"123"));
        when(service.getVersion("owner","client","owner","TASK","task-1","artifact-1","1"))
                .thenReturn(new OutputDetailDTO(summary,"body"));
        String body="""
                {"runId":"run-1","expectedPreviousVersion":"0","title":"safe title",
                 "artifactType":"document","content":"body","artifactId":"artifact-1",
                 "artifactVersion":"1","publishToOwner":true}
                """;

        mvc.perform(post("/agent/tasks/task-1/artifacts")
                        .header(HttpHeaders.AUTHORIZATION,"Bearer "+"a".repeat(43))
                        .header("Idempotency-Key","publish-http-key-03")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.outputId").value("artifact-1"));
        mvc.perform(get("/agent/tasks/task-1/artifacts/artifact-1/versions?limit=10")
                        .principal(jwt("owner","client")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].version").value("1"));
        mvc.perform(get("/agent/tasks/task-1/artifacts/artifact-1/versions/1")
                        .principal(jwt("owner","client")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("body"));

        mvc.perform(post("/agent/tasks/task-1/artifacts")
                        .header(HttpHeaders.AUTHORIZATION,"Bearer "+"a".repeat(43))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"));
        mvc.perform(post("/agent/tasks/task-1/artifacts")
                        .header(HttpHeaders.AUTHORIZATION,"Bearer "+"a".repeat(43))
                        .header("Idempotency-Key","publish-http-key-04")
                        .contentType("text/plain").content(body))
                .andExpect(status().isUnsupportedMediaType())
                ;
    }

    @Test
    void downloadUsesAttachmentNoStoreAndStreamsExactBytes() throws Exception {
        when(service.downloadVersion("owner", "client", "owner", "TASK", "task-1",
                "artifact-1", "1")).thenReturn(new OutputDownloadDTO(
                "safe.txt", "text/plain", BYTES.length, "ecf7",
                new ByteArrayInputStream(BYTES)));

        var response = controller.download("task-1", "artifact-1", "1",
                jwt("owner", "client"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);

        assertEquals("private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .contains("safe.txt"));
        assertArrayEquals(BYTES, out.toByteArray());
    }

    private OutputSummaryDTO summary() {
        return new OutputSummaryDTO(new OutputSourceDTO("TASK", "task-1"),
                "artifact-1", "1", "safe title", "safe.txt", "text/plain", "13",
                "ecf701f727d9e2d77c4aa49ac6fbbcc997278aca010bddeeb961c10cf54d435a",
                "123", "AVAILABLE", "OWNER_SHARE", "TEXT", true, null);
    }

    private JwtAuthenticationToken jwt(String jiacn, String clientId) {
        return jwt(Map.of("jiacn", jiacn, "client_id", clientId));
    }

    private JwtAuthenticationToken jwt(Map<String, Object> claims) {
        Jwt token = Jwt.withTokenValue("user-token").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claims(values -> values.putAll(claims)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
