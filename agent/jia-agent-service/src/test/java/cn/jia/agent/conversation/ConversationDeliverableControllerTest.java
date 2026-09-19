package cn.jia.agent.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationDeliverableControllerTest {
    private ConversationDeliverableReadAdapter adapter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        adapter = mock(ConversationDeliverableReadAdapter.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ConversationDeliverableController(adapter)).build();
    }

    @Test
    void jwtScopeAloneDrivesReadAndResponseLeaksNoSensitivePayload() throws Exception {
        ConversationDeliverableReadAdapter.Item item = new ConversationDeliverableReadAdapter.Item(
                "out-1", "exec-1", "file-1", 1, "a".repeat(64),
                "application/pdf", 42, 1234, "AVAILABLE",
                "WORKSPACE_COMMITTED", "NOT_APPLICABLE", null, null);
        when(adapter.list(new ConversationDeliverableReadAdapter.Scope(
                "0", "client-a", "owner-a"), "7", 100))
                .thenReturn(new ConversationDeliverableReadAdapter.Page(
                        "AVAILABLE", List.of(item), false, null));

        mvc.perform(get("/agent/conversations/7/deliverables")
                        .principal(jwt("owner-a", "client-a", "subject-a", "subject-a")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].executionId").value("exec-1"))
                .andExpect(jsonPath("$.items[0].outputId").value("out-1"))
                .andExpect(jsonPath("$.items[0].fileId").value("file-1"))
                .andExpect(jsonPath("$.items[0].storageUri").doesNotExist())
                .andExpect(jsonPath("$.items[0].leaseToken").doesNotExist())
                .andExpect(jsonPath("$.items[0].originalFilename").doesNotExist())
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.items[0].metadata").doesNotExist());

        verify(adapter).list(new ConversationDeliverableReadAdapter.Scope(
                "0", "client-a", "owner-a"), "7", 100);
    }

    @Test
    void rejectsAnonymousNonJwtMalformedJwtAndNameDriftBeforeAdapter() throws Exception {
        mvc.perform(get("/agent/conversations/7/deliverables"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/conversations/7/deliverables")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "subject-a", "n/a", List.of())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/agent/conversations/7/deliverables")
                        .principal(jwt("owner-a", 7, "subject-a", "subject-a")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/agent/conversations/7/deliverables")
                        .principal(jwt("owner-a", "client-a", "subject-a", "other")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adapter);
    }

    @Test
    void rejectsClientScopeAndMetadataQueryInjection() throws Exception {
        JwtAuthenticationToken principal = jwt("owner-a", "client-a", "subject-a", "subject-a");
        for (String uri : List.of(
                "/agent/conversations/7/deliverables?ownerJiacn=other",
                "/agent/conversations/7/deliverables?clientId=other",
                "/agent/conversations/7/deliverables?metadata=x",
                "/agent/conversations/7/deliverables?taskId=t",
                "/agent/conversations/7/deliverables?limit=0",
                "/agent/conversations/7/deliverables?limit=01",
                "/agent/conversations/7/deliverables?limit=101",
                "/agent/conversations/7/deliverables?limit=1&limit=2")) {
            mvc.perform(get(uri).principal(principal))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(adapter);
    }

    @Test
    void adapterFailuresMapToStableOpaqueStatuses() throws Exception {
        JwtAuthenticationToken principal = jwt("owner-a", "client-a", "subject-a", "subject-a");
        when(adapter.list(any(), anyString(), anyInt()))
                .thenThrow(new ConversationDeliverableReadAdapter.Failure(
                        ConversationDeliverableReadAdapter.Reason.NOT_FOUND));
        mvc.perform(get("/agent/conversations/7/deliverables").principal(principal))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_DELIVERABLE_NOT_FOUND"));

        when(adapter.list(any(), anyString(), anyInt()))
                .thenThrow(new ConversationDeliverableReadAdapter.Failure(
                        ConversationDeliverableReadAdapter.Reason.CORRUPT_STATE,
                        new IllegalStateException("storage_uri=secret")));
        mvc.perform(get("/agent/conversations/7/deliverables").principal(principal))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONVERSATION_DELIVERABLE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Conversation deliverables are unavailable"));
    }

    @Test
    void explicitValidLimitIsForwarded() throws Exception {
        when(adapter.list(any(), anyString(), anyInt()))
                .thenReturn(new ConversationDeliverableReadAdapter.Page(
                        "EMPTY", List.of(), false, null));
        mvc.perform(get("/agent/conversations/7/deliverables?limit=25")
                        .principal(jwt("owner-a", "client-a", "subject-a", "subject-a")))
                .andExpect(status().isOk());
        verify(adapter).list(new ConversationDeliverableReadAdapter.Scope(
                "0", "client-a", "owner-a"), "7", 25);
    }

    private static JwtAuthenticationToken jwt(
            Object owner, Object client, String subject, String authenticationName) {
        Jwt token = Jwt.withTokenValue("fixture-token").header("alg", "none")
                .subject(subject).claim("jiacn", owner).claim("client_id", client).build();
        return new JwtAuthenticationToken(token, List.of(), authenticationName);
    }
}
