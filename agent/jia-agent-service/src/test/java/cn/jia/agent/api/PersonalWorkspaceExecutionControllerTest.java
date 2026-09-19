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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Browser controller contract: empty inputs mean generation rather than a fake file. */
class PersonalWorkspaceExecutionControllerTest {
    private PersonalWorkspaceExecutionService service;
    private PersonalWorkspaceExecutionController controller;

    @BeforeEach
    void setUp() {
        service = mock(PersonalWorkspaceExecutionService.class);
        controller = new PersonalWorkspaceExecutionController(service);
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
        PersonalWorkspaceExecutionService.ExecutionView accepted = new PersonalWorkspaceExecutionService.ExecutionView(
                "pwe_1", "pwe_task_1", "pwe_run_1", null, "agent-a", "QUEUED", null, null, 1L,
                PersonalWorkspaceExecutionProperties.DOCX, List.of(), null);
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

    private static JwtAuthenticationToken jwt() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        return authentication;
    }
}
