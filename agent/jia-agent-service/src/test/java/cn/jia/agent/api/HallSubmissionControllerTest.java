package cn.jia.agent.api;

import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HallSubmissionControllerTest {
    private HallRequestDraftService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(HallRequestDraftService.class);
        mvc = MockMvcBuilders.standaloneSetup(new HallSubmissionController(service)).build();
    }

    @Test
    void taskCreateReceiptReconcilesWithoutAnExecution() throws Exception {
        var scope = new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
        var receipt = new HallRequestDraftService.SubmissionReceipt(
                new HallRequestDraftService.SubmissionReference("TASK", "42"), null,
                new HallRequestDraftService.TaskReference("42", "0"), 1000L);
        when(service.getSubmissionByIdempotencyKey(scope, "task-submit")).thenReturn(receipt);
        mvc.perform(get("/agent/hall/submissions/request").principal(jwt()).header("Idempotency-Key", "task-submit"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.ref.sourceType").value("TASK"))
                .andExpect(jsonPath("$.task.taskId").value("42"))
                .andExpect(jsonPath("$.task.taskVersion").value("0"))
                .andExpect(jsonPath("$.execution").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void reconciliationUsesHeaderOnlyAndReturnsIdenticalReceiptShape() throws Exception {
        var scope = new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
        var receipt = new HallRequestDraftService.SubmissionReceipt(
                new HallRequestDraftService.SubmissionReference("TASK", "task-1"),
                execution("execution-1", "TASK", "task-1"), null, 1_790_000_000_000L);
        when(service.getSubmissionByIdempotencyKey(scope, "submit-key")).thenReturn(receipt);

        mvc.perform(get("/agent/hall/submissions/request").principal(jwt())
                        .header("Idempotency-Key", "submit-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.ref.sourceType").value("TASK"))
                .andExpect(jsonPath("$.ref.sourceId").value("task-1"))
                .andExpect(jsonPath("$.execution.executionMode").value("TASK"))
                .andExpect(jsonPath("$.submittedAt").value(1_790_000_000_000L));
        verify(service).getSubmissionByIdempotencyKey(scope, "submit-key");
    }

    @Test
    void privateCaseShapeIncludesImmutableLineageAndAllowedActions() throws Exception {
        var scope = new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
        var source = new HallRequestDraftService.SourceOutputRef("execution-1", "output-1", "file-1", 1);
        var view = new HallRequestDraftService.CaseView("hpc_1", "标题", 2,
                List.of(new HallRequestDraftService.CaseExecutionView(1, null, null,
                                execution("execution-1", "PRIVATE", null)),
                        new HallRequestDraftService.CaseExecutionView(2, "execution-1", source,
                                execution("execution-2", "PRIVATE", null))),
                List.of("VIEW", "CREATE_REVISION"),
                new HallRequestDraftService.CaseSourceRef("results"));
        when(service.getCase(scope, "hpc_1")).thenReturn(view);

        mvc.perform(get("/agent/hall/cases/hpc_1").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.caseId").value("hpc_1"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.executions[1].parentExecutionId").value("execution-1"))
                .andExpect(jsonPath("$.executions[1].sourceOutputRef.outputId").value("output-1"))
                .andExpect(jsonPath("$.allowedActions[1]").value("CREATE_REVISION"));
    }

    @Test
    void invisibleCaseIs404AndIdentityOrQueryInjectionFailClosed() throws Exception {
        var scope = new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
        when(service.getCase(scope, "foreign")).thenThrow(new HallRequestDraftService.Failure(
                HallRequestDraftService.Reason.NOT_FOUND));
        mvc.perform(get("/agent/hall/cases/foreign").principal(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("HALL_RESOURCE_NOT_FOUND"));

        mvc.perform(get("/agent/hall/cases/hpc_1?ownerJiacn=victim").principal(jwt()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/hall/cases/hpc_1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void executionResultsExposeOnlyFixedManifestMetadata() throws Exception {
        var scope = new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
        var view = new HallRequestDraftService.ExecutionResultsView(
                "execution-1", "OUTPUT_COMMITTED", "pwe_m_manifest",
                List.of(new HallRequestDraftService.ResultItemView(
                        "output_1", "file-1", 1, "application/pdf", "result.pdf",
                        512, "a".repeat(64), "AVAILABLE")),
                List.of("VIEW", "CREATE_REVISION"));
        when(service.getExecutionResults(scope, "execution-1")).thenReturn(view);

        mvc.perform(get("/agent/hall/executions/execution-1/results").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.executionId").value("execution-1"))
                .andExpect(jsonPath("$.manifestId").value("pwe_m_manifest"))
                .andExpect(jsonPath("$.items[0].fileId").value("file-1"))
                .andExpect(jsonPath("$.items[0].fileVersion").value(1))
                .andExpect(jsonPath("$.items[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].storageUri").doesNotExist());
        verify(service).getExecutionResults(scope, "execution-1");

        mvc.perform(get("/agent/hall/executions/execution-1/results?fileId=victim")
                        .principal(jwt()))
                .andExpect(status().isBadRequest());

        when(service.getExecutionResults(scope, "foreign")).thenThrow(
                new HallRequestDraftService.Failure(HallRequestDraftService.Reason.NOT_FOUND));
        mvc.perform(get("/agent/hall/executions/foreign/results").principal(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HALL_RESOURCE_NOT_FOUND"));
        mvc.perform(get("/agent/hall/executions/execution-1/results"))
                .andExpect(status().isUnauthorized());
    }

    private static PersonalWorkspaceExecutionService.ExecutionView execution(
            String id, String mode, String businessTaskId) {
        return new PersonalWorkspaceExecutionService.ExecutionView(id,
                businessTaskId == null ? "private-task" : businessTaskId,
                "run-1", null, "agent-a", "QUEUED", null, null, 1,
                "application/pdf", List.of(), null, mode, businessTaskId, null, null);
    }

    private static JwtAuthenticationToken jwt() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(token);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
