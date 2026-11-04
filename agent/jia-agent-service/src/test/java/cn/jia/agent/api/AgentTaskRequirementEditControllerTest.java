package cn.jia.agent.api;

import cn.jia.agent.service.AgentTaskRequirementEditService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTaskRequirementEditControllerTest {
    AgentTaskRequirementEditService service;MockMvc mvc;
    @BeforeEach void setup() {
        service=mock(AgentTaskRequirementEditService.class);
        mvc=MockMvcBuilders.standaloneSetup(new AgentTaskRequirementEditController(service))
                .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(),new cn.jia.core.config.ApiRequestEnvelopeAdvice(),new cn.jia.core.config.ExceptionHandlerAdvice())
                .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
        var context=new EsContext();context.setClientId("client");context.setJiacn("owner");EsContextHolder.setContext(context);
    }
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }
    JwtAuthenticationToken owner() {
        return new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg","RS256").subject("owner")
                .claim("jiacn","owner").claim("client_id","client").claim("tenant_id","0")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build());
    }
    String body(String version) { return "{\"protocolVersion\":1,\"requestId\":\"edit-test\",\"data\":{\"expectedTaskVersion\":"+version+",\"expectedRequirementRevision\":\"1\",\"title\":\"事项标题\",\"description\":\"需求\",\"attachments\":[{\"fileId\":\"file\",\"version\":1}]}}"; }
    String fixtureBody() throws Exception {
        try (var in=getClass().getResourceAsStream("/contracts/matter-requirement-edit-v1.json")) {
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            return json.writeValueAsString(json.readTree(in).get("request").get("body"));
        }
    }
    @Test void saveUsesQEnvelopeAndCanonicalLongStrings() throws Exception {
        when(service.save(any(),eq("1"),eq("edit-key"),any())).thenReturn(new AgentTaskRequirementEditService.View("1","1","2","事项标题","需求",List.of(new AgentTaskRequirementEditService.Attachment("file",1))));
        mvc.perform(put("/agent/tasks/1/requirements/edit").principal(owner()).header("Idempotency-Key","edit-key")
                .contentType(MediaType.APPLICATION_JSON).content(fixtureBody()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.protocolVersion").value(1)).andExpect(jsonPath("$.data.taskVersion").value("1"))
                .andExpect(jsonPath("$.data.requirementRevision").value("2")).andExpect(jsonPath("$.data.attachments[0].version").value(1));
    }
    @Test void unauthenticatedAndNumericVersionsAreNotAccepted() throws Exception {
        mvc.perform(get("/agent/tasks/1/requirements/edit")).andExpect(status().isUnauthorized());
        mvc.perform(put("/agent/tasks/1/requirements/edit").principal(owner()).header("Idempotency-Key","edit-key")
                .contentType(MediaType.APPLICATION_JSON).content(body("0"))).andExpect(status().isBadRequest());
        verify(service,never()).save(any(),anyString(),anyString(),any());
    }
    @Test void concurrentStateChangeIs409NotSuccess() throws Exception {
        when(service.save(any(),anyString(),anyString(),any())).thenThrow(new AgentTaskRequirementEditService.Failure(AgentTaskRequirementEditService.Reason.NOT_EDITABLE));
        mvc.perform(put("/agent/tasks/1/requirements/edit").principal(owner()).header("Idempotency-Key","edit-key")
                .contentType(MediaType.APPLICATION_JSON).content(body("\"0\""))).andExpect(status().isConflict());
    }
}
