package cn.jia.chat.api;

import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.dto.OutputDetailDTO;
import cn.jia.agent.output.dto.OutputDownloadDTO;
import cn.jia.agent.output.dto.OutputPageDTO;
import cn.jia.agent.output.dto.OutputPublishDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.output.dto.OutputSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationOutputControllerTest {
    private OutputDeliveryService service;
    private ConversationOutputController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OutputDeliveryService.class);
        controller = new ConversationOutputController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void userJwtListAndDetailUseOwnedConversationScope() throws Exception {
        OutputSummaryDTO summary = summary();
        when(service.list("owner", "client", "owner", "CONVERSATION", "101", null, null))
                .thenReturn(new OutputPageDTO(List.of(summary), null, "123"));
        when(service.getVersion("owner", "client", "owner", "CONVERSATION", "101",
                "output-1", "1")).thenReturn(new OutputDetailDTO(summary, "body"));
        when(service.listVersions("owner","client","owner","CONVERSATION","101",
                "output-1",null,20)).thenReturn(new OutputPageDTO(List.of(summary),null,"123"));

        mvc.perform(get("/chat/conversations/101/outputs").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.items[0].source.type").value("CONVERSATION"));
        mvc.perform(get("/chat/conversations/101/outputs/output-1/versions/1")
                        .principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("body"));
        mvc.perform(get("/chat/conversations/101/outputs/output-1/versions?limit=20")
                        .principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].version").value("1"));
    }

    @Test
    void strictChatPublicationMapsFrozenNamesAndRejectsTaskOnlyFields() throws Exception {
        OutputSummaryDTO summary = summary();
        when(service.publish(eq("Bearer " + "c".repeat(43)), eq("chat-http-key-0001"),
                eq("CONVERSATION"), eq("101"), any(OutputPublishDTO.class))).thenReturn(summary);
        String body = """
                {"runId":"chat-run","expectedPreviousVersion":"0","title":"chat result",
                 "artifactType":"document","content":"body","outputId":"output-1","version":"1"}
                """;

        mvc.perform(post("/chat/conversations/101/outputs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + "c".repeat(43))
                        .header("Idempotency-Key", "chat-http-key-0001")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.outputId").value("output-1"));
        verify(service).publish(eq("Bearer " + "c".repeat(43)), eq("chat-http-key-0001"),
                eq("CONVERSATION"), eq("101"), any(OutputPublishDTO.class));

        mvc.perform(post("/chat/conversations/101/outputs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + "c".repeat(43))
                        .header("Idempotency-Key", "chat-http-key-0002")
                        .contentType("application/json")
                        .content(body.substring(0, body.lastIndexOf('}'))
                                + ",\"publishToOwner\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"));
    }

    @Test
    void missingJwtUsesFrozenUnauthorizedEnvelope() throws Exception {
        mvc.perform(get("/chat/conversations/101/outputs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("OUTPUT_AUTH_UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void downloadRouteStreamsExactBytesWithPrivateNoStoreHeaders() throws Exception {
        byte[] bytes="hello world!\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.downloadVersion("owner","client","owner","CONVERSATION","101",
                "output-1","1")).thenReturn(new OutputDownloadDTO("chat.txt","text/plain",
                bytes.length,"ecf7",new ByteArrayInputStream(bytes)));

        var response=controller.download("101","output-1","1",jwt());
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        response.getBody().writeTo(out);

        assertEquals("private, no-store",response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertArrayEquals(bytes,out.toByteArray());
    }

    private OutputSummaryDTO summary() {
        return new OutputSummaryDTO(new OutputSourceDTO("CONVERSATION", "101"),
                "output-1", "1", "chat result", null, "text/plain", "4",
                "230d8358dc8e8890b4c4f8443af7f85fba0b6b2aef24fcd3f77982a8f9f43f77",
                "123", "AVAILABLE", "CONVERSATION_OUTPUT", "TEXT", true, null);
    }

    private JwtAuthenticationToken jwt() {
        Jwt token = Jwt.withTokenValue("user-token").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claims(values -> values.putAll(Map.of(
                        "jiacn", "owner", "client_id", "client"))).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
