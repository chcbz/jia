package cn.jia.agent.api;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Browser contract: capability reporting is authenticated, and empty inputs mean generation rather than a fake file. */
class PersonalWorkspaceExecutionControllerTest {
    private PersonalWorkspaceExecutionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PersonalWorkspaceExecutionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PersonalWorkspaceExecutionController(service)).build();
    }

    @Test
    void capabilitiesReturnOnlyServerConfirmedFormatsWithPrivateNoStoreResponse() throws Exception {
        when(service.capabilities()).thenReturn(new PersonalWorkspaceExecutionService.ExecutionCapabilities(
                List.of(PersonalWorkspaceExecutionProperties.DOCX), true));

        mvc.perform(get("/agent/personal-workspace/executions/capabilities").principal(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.allowedMimeTypes[0]").value(PersonalWorkspaceExecutionProperties.DOCX))
                .andExpect(jsonPath("$.generationEnabled").value(true));
        verify(service).capabilities();
    }

    @Test
    void explicitEmptyInputListCreatesAFileGenerationCommand() throws Exception {
        PersonalWorkspaceExecutionService.ExecutionView accepted = new PersonalWorkspaceExecutionService.ExecutionView(
                "pwe_1", "pwe_task_1", "pwe_run_1", null, "agent-a", "QUEUED", 1L,
                PersonalWorkspaceExecutionProperties.DOCX, List.of(), null);
        when(service.create(any(), any(), eq("create-key"))).thenReturn(accepted);

        mvc.perform(post("/agent/personal-workspace/executions")
                        .principal(jwt()).header("Idempotency-Key", "create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetAgentId":"agent-a","instruction":"生成项目介绍文档",
                                 "outputContentMimeType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                 "inputs":[]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.executionId").value("pwe_1"))
                .andExpect(jsonPath("$.outputContentMimeType").value(PersonalWorkspaceExecutionProperties.DOCX))
                .andExpect(jsonPath("$.inputs").isEmpty());

        ArgumentCaptor<PersonalWorkspaceExecutionService.OwnerScope> scope = ArgumentCaptor.forClass(
                PersonalWorkspaceExecutionService.OwnerScope.class);
        ArgumentCaptor<PersonalWorkspaceExecutionService.CreateCommand> command = ArgumentCaptor.forClass(
                PersonalWorkspaceExecutionService.CreateCommand.class);
        verify(service).create(scope.capture(), command.capture(), eq("create-key"));
        assertEquals(new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a"), scope.getValue());
        assertEquals(List.of(), command.getValue().inputs());
        assertEquals(PersonalWorkspaceExecutionProperties.DOCX, command.getValue().outputContentMimeType());
    }

    private static JwtAuthenticationToken jwt() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        return new JwtAuthenticationToken(jwt);
    }
}
