package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskFormalDeliveryControllerTest {
    private static final String TENANT = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String ACTOR = "agent-a";
    private static final String TASK = "task-a";

    private AgentTaskFormalDeliveryService submission;
    private AgentTaskFormalDeliveryDecisionService decision;
    private AgentTaskFormalDeliveryReadService read;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        submission = mock(AgentTaskFormalDeliveryService.class);
        decision = mock(AgentTaskFormalDeliveryDecisionService.class);
        read = mock(AgentTaskFormalDeliveryReadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentTaskFormalDeliveryController(
                submission, decision, read)).build();
    }

    @Test
    void submitDerivesOpaqueStableDeliveryIdentityAndNeverReturnsLease() throws Exception {
        when(submission.submit(eq("0"), eq(CLIENT), eq(TENANT), eq(TASK), eq(ACTOR), any()))
                .thenReturn(view("submitted", null, null));
        String body = """
                {"runId":"run-a","workItemId":"work-a","leaseToken":"lease-secret-a",
                 "expectedTaskVersion":3,"expectedWorkItemVersion":7,"summary":"summary",
                 "manifestArtifactId":"manifest-a","manifestArtifactVersion":1,
                 "items":[{"artifactId":"manifest-a","artifactVersion":1,
                 "contentHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "purpose":"manifest"}]}
                """;

        mvc.perform(post("/agent/tasks/" + TASK + "/formal-deliveries")
                        .header("Idempotency-Key", "formal-key-0001")
                        .contentType("application/json").content(body).principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.deliveryId").value(org.hamcrest.Matchers.startsWith("fd_")))
                .andExpect(jsonPath("$.leaseToken").doesNotExist())
                .andExpect(jsonPath("$.submissionDigest").doesNotExist());

        ArgumentCaptor<cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO> command =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO.class);
        verify(submission).submit(eq("0"), eq(CLIENT), eq(TENANT), eq(TASK), eq(ACTOR), command.capture());
        assertEquals("fd_de3a458cf563ceb3f0d65b59b4fdad01913a98b3efc690265730518a31209a3c",
                command.getValue().getDeliveryId());
        assertEquals("lease-secret-a", command.getValue().getLeaseToken());
    }

    @Test
    void ownerDecisionUsesTenantNotBrowserSuppliedIdentity() throws Exception {
        when(decision.decide(eq("0"), eq(CLIENT), eq(TASK), eq(TENANT), any()))
                .thenReturn(view("changes_requested", 2_000L, "add tests"));
        String body = """
                {"expectedTaskVersion":4,"expectedDeliveryVersion":0,
                 "decision":"changes_requested","reviewReason":"add tests"}
                """;

        mvc.perform(post("/agent/tasks/" + TASK + "/formal-deliveries/delivery-a/decision")
                        .header("Idempotency-Key", "decision-key-0001")
                        .contentType("application/json").content(body).principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("changes_requested"));
        verify(decision).decide(eq("0"), eq(CLIENT), eq(TASK), eq(TENANT), any());
    }

    @Test
    void malformedScopeAndUnknownQueryFailBeforeAnyService() throws Exception {
        mvc.perform(get("/agent/tasks/" + TASK + "/formal-deliveries?tenantId=forged")
                        .principal(jwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/" + TASK + "/formal-deliveries")
                        .principal(jwtClaims(TENANT, CLIENT, "different-name")))
                .andExpect(status().isForbidden());
        verify(read, never()).listForTaskOwner(any(), any(), any(), any());
    }

    private static AgentTaskFormalDeliveryViewDTO view(
            String state, Long reviewedAt, String reviewReason) {
        AgentTaskFormalDeliveryItemDTO item = new AgentTaskFormalDeliveryItemDTO();
        item.setArtifactId("manifest-a"); item.setArtifactVersion(1);
        item.setContentHash("a".repeat(64)); item.setPurpose("manifest");
        AgentTaskFormalDeliveryViewDTO view = new AgentTaskFormalDeliveryViewDTO();
        view.setTaskId(TASK); view.setWorkItemId("work-a"); view.setDeliveryId("fd_test");
        view.setRevision(1L); view.setState(state); view.setRunId("run-a");
        view.setProducerAgentId(ACTOR); view.setSummary("summary");
        view.setManifestArtifactId("manifest-a"); view.setManifestArtifactVersion(1);
        view.setSubmittedAt(1_000L); view.setReviewedAt(reviewedAt);
        view.setReviewReason(reviewReason); view.setTaskVersion(4L); view.setWorkItemVersion(8L);
        view.setItems(List.of(item));
        return view;
    }

    private static JwtAuthenticationToken jwt() {
        return jwtClaims(TENANT, CLIENT, ACTOR);
    }

    private static JwtAuthenticationToken jwtClaims(String tenant, String client, String subject) {
        Jwt token = new Jwt("token", Instant.ofEpochSecond(1), Instant.ofEpochSecond(9_999_999_999L),
                Map.of("alg", "none"), Map.of("jiacn", tenant, "client_id", client, "sub", subject));
        return new JwtAuthenticationToken(token, List.of()) {
            @Override public String getName() { return ACTOR; }
        };
    }
}
