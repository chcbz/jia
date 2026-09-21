package cn.jia.agent.api;

import cn.jia.agent.service.HallRequestDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HallRequestDraftControllerTest {
    private HallRequestDraftService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(HallRequestDraftService.class);
        mvc = MockMvcBuilders.standaloneSetup(new HallRequestDraftController(service)).build();
    }

    @Test
    void createUsesOnlyJwtScopeAndReturnsFrozenDraftShapeWithNoStore() throws Exception {
        when(service.create(any(), any(), eq("create-key"))).thenReturn(view("hdr_1", 1, "EDITING"));

        mvc.perform(post("/agent/hall/drafts")
                        .principal(jwt("owner-a", "client-a"))
                        .header("Idempotency-Key", "create-key")
                        .contentType("application/json")
                        .content("""
                                {"kind":"CREATE","originRef":"map","title":"方案",
                                 "instruction":"私密正文","targetAgentId":null,
                                 "outputMime":"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                 "inputs":[]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.draftId").value("hdr_1"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.kind").value("CREATE"))
                .andExpect(jsonPath("$.editableFields.title").value("方案"))
                .andExpect(jsonPath("$.sourceSummary.originRef").value("map"))
                .andExpect(jsonPath("$.submissionRef").doesNotExist());

        ArgumentCaptor<HallRequestDraftService.OwnerScope> scope = ArgumentCaptor.forClass(
                HallRequestDraftService.OwnerScope.class);
        ArgumentCaptor<HallRequestDraftService.CreateCommand> command = ArgumentCaptor.forClass(
                HallRequestDraftService.CreateCommand.class);
        verify(service).create(scope.capture(), command.capture(), eq("create-key"));
        assertEquals(new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a"),
                scope.getValue());
        assertEquals("CREATE", command.getValue().kind());
        assertEquals("私密正文", command.getValue().editableFields().instruction());
        assertEquals(List.of(), command.getValue().editableFields().inputs());
    }

    @Test
    void listContractIsSummaryOnlyAndForwardsTheSingleOpaqueCursor() throws Exception {
        HallRequestDraftService.DraftSummary summary = new HallRequestDraftService.DraftSummary(
                "hdr_2", 4, "EDITING", 1_790_000_000_000L, "CREATE", "摘要标题",
                "agent-a", "application/pdf",
                new HallRequestDraftService.SourceSummary("files", null, null, null, null, null));
        when(service.list(new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a"),
                "opaque-cursor")).thenReturn(new HallRequestDraftService.DraftPage(
                        List.of(summary), "next-cursor"));

        mvc.perform(get("/agent/hall/drafts?cursor=opaque-cursor").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.items[0].draftId").value("hdr_2"))
                .andExpect(jsonPath("$.items[0].title").value("摘要标题"))
                .andExpect(jsonPath("$.items[0].instruction").doesNotExist())
                .andExpect(jsonPath("$.items[0].inputs").doesNotExist())
                .andExpect(jsonPath("$.items[0].editableFields").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"));
    }

    @Test
    void replaceRequiresQuotedIfMatchAndMapsStaleCasTo412WithoutRetry() throws Exception {
        when(service.replace(any(), eq("hdr_1"), eq(3L), any())).thenThrow(
                new HallRequestDraftService.Failure(
                        HallRequestDraftService.Reason.REVISION_CHANGED));

        mvc.perform(put("/agent/hall/drafts/hdr_1").principal(jwt())
                        .header(HttpHeaders.IF_MATCH, "\"3\"")
                        .contentType("application/json")
                        .content("{\"title\":\"new\",\"instruction\":\"body\",\"inputs\":[]}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("HALL_DRAFT_REVISION_CHANGED"))
                .andExpect(jsonPath("$.retryable").value(false));

        for (String malformed : List.of("3", "W/\"3\"", "\"0\"", "\"03\"", "\"x\"")) {
            mvc.perform(put("/agent/hall/drafts/hdr_1").principal(jwt())
                            .header(HttpHeaders.IF_MATCH, malformed)
                            .contentType("application/json")
                            .content("{\"title\":\"x\",\"instruction\":\"y\",\"inputs\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
    }

    @Test
    void strictJsonRejectsUnknownScopeFieldsDuplicatesAndWrongInputVersions() throws Exception {
        List<String> bodies = List.of(
                "{\"kind\":\"CREATE\",\"originRef\":\"map\",\"inputs\":[],\"ownerJiacn\":\"victim\"}",
                "{\"kind\":\"CREATE\",\"kind\":\"TASK_ACTION\",\"originRef\":\"map\",\"inputs\":[]}",
                "{\"kind\":\"CREATE\",\"originRef\":\"map\",\"inputs\":[{\"fileId\":\"f1\",\"version\":\"1\"}]}"
        );
        for (String body : bodies) {
            mvc.perform(post("/agent/hall/drafts").principal(jwt())
                            .header("Idempotency-Key", "key")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void discardRequiresNumericExpectedRevisionAndPreservesIdempotencyKey() throws Exception {
        when(service.discard(any(), eq("hdr_1"), eq(7L), eq("discard-key")))
                .thenReturn(view("hdr_1", 8, "DISCARDED"));

        mvc.perform(post("/agent/hall/drafts/hdr_1/discard").principal(jwt())
                        .header("Idempotency-Key", "discard-key")
                        .contentType("application/json")
                        .content("{\"expectedRevision\":7}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.state").value("DISCARDED"))
                .andExpect(jsonPath("$.revision").value(8));

        verify(service).discard(new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a"),
                "hdr_1", 7, "discard-key");
    }

    @Test
    void sourceAclFailureIsReadable422WithOnlySafeDetails() throws Exception {
        when(service.create(any(), any(), eq("source-key"))).thenThrow(
                new HallRequestDraftService.Failure(
                        HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                        java.util.Map.of("field", "inputs", "sourceType", "FILE")));

        mvc.perform(post("/agent/hall/drafts").principal(jwt())
                        .header("Idempotency-Key", "source-key")
                        .contentType("application/json")
                        .content("{\"kind\":\"CREATE\",\"originRef\":\"files\",\"inputs\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("HALL_DRAFT_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.details.field").value("inputs"))
                .andExpect(jsonPath("$.details.sourceType").value("FILE"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void missingAuthenticationIs401MalformedTrustedClaimsAre403AndBothAreNoStore() throws Exception {
        mvc.perform(get("/agent/hall/drafts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        mvc.perform(get("/agent/hall/drafts").principal(jwt("owner-a ", "client-a")))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verifyNoInteractions(service);
    }

    @Test
    void submitUsesFrozenBodyKeyAndReturns202WhileRunRouteRemainsAbsent() throws Exception {
        HallRequestDraftService.SubmissionReceipt receipt = new HallRequestDraftService.SubmissionReceipt(
                new HallRequestDraftService.SubmissionReference("PRIVATE_CASE", "hpc_1"),
                execution("pwe_1", "PRIVATE", null), null, 1_790_000_000_000L);
        when(service.submit(any(), eq("hdr_1"), eq(3L), eq(true), eq("submit-key")))
                .thenReturn(receipt);

        mvc.perform(post("/agent/hall/drafts/hdr_1/submit").principal(jwt())
                        .header("Idempotency-Key", "submit-key")
                        .contentType("application/json")
                        .content("{\"expectedRevision\":3,\"authorizationAcknowledgement\":true}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.ref.sourceType").value("PRIVATE_CASE"))
                .andExpect(jsonPath("$.ref.sourceId").value("hpc_1"))
                .andExpect(jsonPath("$.execution.executionId").value("pwe_1"))
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.submittedAt").value(1_790_000_000_000L));
        verify(service).submit(new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a"),
                "hdr_1", 3, true, "submit-key");

        for (String body : List.of("{}", "{\"expectedRevision\":3}",
                "{\"expectedRevision\":3,\"authorizationAcknowledgement\":1}")) {
            mvc.perform(post("/agent/hall/drafts/hdr_1/submit").principal(jwt())
                            .header("Idempotency-Key", "other-key")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/agent/hall/drafts/hdr_1/run").principal(jwt())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }

    private static HallRequestDraftService.DraftView view(String id, long revision, String state) {
        return new HallRequestDraftService.DraftView(id, revision, state, 1_790_000_000_000L,
                "CREATE", new HallRequestDraftService.EditableFields("方案", "私密正文", null,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", List.of()),
                new HallRequestDraftService.SourceSummary("map", null, null, null, null, null), null);
    }

    private static cn.jia.agent.service.PersonalWorkspaceExecutionService.ExecutionView execution(
            String id, String mode, String taskId) {
        return new cn.jia.agent.service.PersonalWorkspaceExecutionService.ExecutionView(
                id, taskId == null ? "pwe_task_1" : taskId, "run_1", null, "agent-a",
                "QUEUED", null, null, 1, "application/pdf", List.of(), null,
                mode, taskId, null, null);
    }

    private static JwtAuthenticationToken jwt() { return jwt("owner-a", "client-a"); }
    private static JwtAuthenticationToken jwt(String owner, String client) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
