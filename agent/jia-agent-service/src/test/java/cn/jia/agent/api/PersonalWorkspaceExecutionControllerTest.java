package cn.jia.agent.api;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Browser controller contract: empty inputs mean generation rather than a fake file. */
class PersonalWorkspaceExecutionControllerTest {
    private PersonalWorkspaceExecutionService service;
    private PersonalWorkspaceExecutionController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(PersonalWorkspaceExecutionService.class);
        controller = new PersonalWorkspaceExecutionController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void capabilitiesReturnOnlyServerConfirmedFormatsWithPrivateNoStoreResponse() {
        when(service.capabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(
                List.of(PersonalWorkspaceExecutionProperties.DOCX),
                List.of("application/pdf", PersonalWorkspaceExecutionProperties.DOCX), true));

        var response = controller.capabilities(jwt());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals(List.of(PersonalWorkspaceExecutionProperties.DOCX), response.getBody().allowedMimeTypes());
        assertEquals(List.of("application/pdf", PersonalWorkspaceExecutionProperties.DOCX),
                response.getBody().inputMimeTypes());
        assertEquals(true, response.getBody().generationEnabled());
        verify(service).capabilities();
    }

    @Test
    void explicitEmptyInputListCreatesAFileGenerationCommand() {
        PersonalWorkspaceExecutionService.ExecutionView accepted = executionView("pwe_1");
        when(service.create(any(), any(), eq("create-key"))).thenReturn(accepted);

        var response = controller.create(new PersonalWorkspaceExecutionController.CreateRequest(null, "agent-a", null,
                "生成项目介绍文档", PersonalWorkspaceExecutionProperties.DOCX, List.of()), "create-key", jwt());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("pwe_1", response.getBody().executionId());
        assertEquals(List.of(), response.getBody().inputs());
        ArgumentCaptor<PersonalWorkspaceExecutionService.OwnerScope> scope = ArgumentCaptor.forClass(
                PersonalWorkspaceExecutionService.OwnerScope.class);
        ArgumentCaptor<PersonalWorkspaceExecutionService.CreateCommand> command = ArgumentCaptor.forClass(
                PersonalWorkspaceExecutionService.CreateCommand.class);
        verify(service).create(scope.capture(), command.capture(), eq("create-key"));
        assertEquals(new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a"), scope.getValue());
        assertEquals(List.of(), command.getValue().inputs());
        assertEquals(PersonalWorkspaceExecutionProperties.DOCX, command.getValue().outputContentMimeType());
    }

    @Test
    void historyDefaultsToTwentyAndSerializesOnlyTheFrozenSummaryAllowList() throws Exception {
        var summary = new PersonalWorkspaceExecutionService.ExecutionSummary(
                "pwe_fbdca6bb2b654ee2ad555c576af7d74c", "agent-a", "QUEUED",
                PersonalWorkspaceExecutionProperties.DOCX, 1_790_000_000_000L);
        var page = new PersonalWorkspaceExecutionService.ExecutionHistoryView(List.of(summary),
                new PersonalWorkspaceExecutionService.ExecutionCursor(
                        summary.createdAt(), summary.executionId()));
        when(service.list(new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a"),
                20, null, null)).thenReturn(page);

        mvc.perform(get("/agent/personal-workspace/executions").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.items[0].executionId").value(summary.executionId()))
                .andExpect(jsonPath("$.items[0].targetAgentId").value("agent-a"))
                .andExpect(jsonPath("$.items[0].state").value("QUEUED"))
                .andExpect(jsonPath("$.items[0].outputContentMimeType")
                        .value(PersonalWorkspaceExecutionProperties.DOCX))
                .andExpect(jsonPath("$.items[0].createdAt").value(summary.createdAt()))
                .andExpect(jsonPath("$.items[0].instruction").doesNotExist())
                .andExpect(jsonPath("$.items[0].inputs").doesNotExist())
                .andExpect(jsonPath("$.items[0].runtimeCommand").doesNotExist())
                .andExpect(jsonPath("$.items[0].leaseToken").doesNotExist())
                .andExpect(jsonPath("$.nextCursor.createdAt").value(summary.createdAt()))
                .andExpect(jsonPath("$.nextCursor.executionId").value(summary.executionId()));
    }

    @Test
    void malformedHistoryBoundsAndUnpairedCursorsFailBeforeService() throws Exception {
        for (String path : List.of(
                "/agent/personal-workspace/executions?limit=0",
                "/agent/personal-workspace/executions?limit=101",
                "/agent/personal-workspace/executions?limit=01",
                "/agent/personal-workspace/executions?beforeCreatedAt=1000",
                "/agent/personal-workspace/executions?beforeExecutionId=pwe_1",
                "/agent/personal-workspace/executions?beforeCreatedAt=-1&beforeExecutionId=pwe_1")) {
            mvc.perform(get(path).principal(jwt()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        // queryParam supplies the decoded servlet parameter. Embedding "%20" in the MockMvc
        // URI string leaves a literal percent sequence and does not model a decoded leading space.
        mvc.perform(get("/agent/personal-workspace/executions")
                        .queryParam("beforeCreatedAt", "1000")
                        .queryParam("beforeExecutionId", " pwe_1")
                        .principal(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void missingOrMalformedJwtScopeFailsClosedBeforeHistoryLookup() throws Exception {
        mvc.perform(get("/agent/personal-workspace/executions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/agent/personal-workspace/executions").principal(jwt("owner-a ", "client-a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(get("/agent/personal-workspace/executions").principal(jwt("owner-a", "client-a ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void idempotencyRequestUsesLiteralRouteAndNeverFallsThroughToExecutionId() throws Exception {
        var existing = executionView("pwe_existing");
        var owner = new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a");
        when(service.getByIdempotencyKey(owner, "paid-create-key")).thenReturn(existing);

        mvc.perform(get("/agent/personal-workspace/executions/request")
                        .header("Idempotency-Key", "paid-create-key").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.executionId").value("pwe_existing"));

        verify(service).getByIdempotencyKey(owner, "paid-create-key");
        verify(service, never()).get(any(), eq("request"));
        verify(service, never()).create(any(), any(), any());
    }

    @Test
    void idempotencyRequestRequiresKeyAndMapsMissingExecutionToFrozen404() throws Exception {
        mvc.perform(get("/agent/personal-workspace/executions/request").principal(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        when(service.getByIdempotencyKey(any(), eq("unknown-key"))).thenThrow(
                new PersonalWorkspaceExecutionService.Failure(
                        PersonalWorkspaceExecutionService.Reason.NOT_FOUND));
        mvc.perform(get("/agent/personal-workspace/executions/request")
                        .header("Idempotency-Key", "unknown-key").principal(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));
    }

    private static PersonalWorkspaceExecutionService.ExecutionView executionView(String executionId) {
        return new PersonalWorkspaceExecutionService.ExecutionView(
                executionId, "pwe_task_1", "pwe_run_1", null, "agent-a", "QUEUED",
                null, null, 1L, PersonalWorkspaceExecutionProperties.DOCX, List.of(), null);
    }

    private static JwtAuthenticationToken jwt() {
        return jwt("owner-a", "client-a");
    }

    private static JwtAuthenticationToken jwt(String owner, String client) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
