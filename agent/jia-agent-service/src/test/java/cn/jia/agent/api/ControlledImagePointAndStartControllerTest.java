package cn.jia.agent.api;

import cn.jia.agent.entity.ControlledImagePointAndStartDTO;
import cn.jia.agent.service.ControlledImagePointAndStartService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlledImagePointAndStartControllerTest {
    private ControlledImagePointAndStartService service;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service=mock(ControlledImagePointAndStartService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ControlledImagePointAndStartController(service)).build();
        EsContext context=new EsContext();context.setTenantId("0");context.setClientId("client-a");
        context.setJiacn("owner-a");EsContextHolder.setContext(context);
    }
    @AfterEach void clear() { EsContextHolder.clearContext(); }

    @Test void exactWrapperRoutesToV2ServiceAndPreservesOriginalKey() throws Exception {
        when(service.submit(any(),eq("task-1"),eq("assignment-key"),any()))
                .thenReturn(new ControlledImagePointAndStartService.Result(null,false));
        mvc.perform(post("/agent/tasks/task-1/point-and-start-controlled-image")
                        .principal(jwt("owner-a","client-a"))
                        .header("Idempotency-Key","assignment-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body(0,"1")))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"));
        var captured=org.mockito.ArgumentCaptor.forClass(ControlledImagePointAndStartDTO.Request.class);
        verify(service).submit(eq(new cn.jia.agent.service.AgentTaskExecutionGrantService.Scope(
                "0","client-a","owner-a")),eq("task-1"),eq("assignment-key"),captured.capture());
        assertEquals("GENERATE_IMAGE",captured.getValue().assignment().getInitialOperation());
        assertEquals("1",captured.getValue().providerConsent().expectedVersion());
    }

    @Test void unknownDuplicateUnsafeAndSeventeenInputsAreRejectedBeforeService() throws Exception {
        String valid=body(0,"1");
        for (String invalid:List.of(
                valid.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                valid.replace("\"providerConsent\"","\"costAuthorizationRef\":\"fake\",\"providerConsent\""),
                valid.replace("\"expectedVersion\":\"1\"","\"expectedVersion\":1"),
                valid.replace("\"expectedVersion\":\"1\"","\"expectedVersion\":\"01\""),
                valid.replace("\"expectedVersion\":\"1\"","\"expectedVersion\":\"9007199254740992\""),
                valid.replace("\"expectedTaskVersion\":6","\"expectedTaskVersion\":9007199254740992"),
                body(1,"1").replace("\"version\":1","\"version\":2147483648"),
                body(17,"1"),valid+" {}")) {
            mvc.perform(post("/agent/tasks/task-1/point-and-start-controlled-image")
                            .principal(jwt("owner-a","client-a"))
                            .header("Idempotency-Key","assignment-key")
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CONTROLLED_IMAGE_BRIDGE_BAD_REQUEST"));
        }
        verifyNoInteractions(service);
    }

    @Test void originalKeyGetIsOwnerAuthenticatedQueryOnly() throws Exception {
        when(service.get(any(),eq("task-1"),eq("assignment-key"))).thenReturn(null);
        mvc.perform(get("/agent/tasks/task-1/point-and-start-controlled-image/request")
                        .principal(jwt("owner-a","client-a"))
                        .header("Idempotency-Key","assignment-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"));
        mvc.perform(get("/agent/tasks/task-1/point-and-start-controlled-image/request?owner=victim")
                        .principal(jwt("owner-a","client-a"))
                        .header("Idempotency-Key","assignment-key"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/task-1/point-and-start-controlled-image/request")
                        .principal(jwt("owner-b","client-a"))
                        .header("Idempotency-Key","assignment-key"))
                .andExpect(status().isForbidden());
        verify(service,times(1)).get(any(),eq("task-1"),eq("assignment-key"));
        verify(service,never()).submit(any(),anyString(),anyString(),any());
    }

    private static String body(int inputs,String version) {
        StringBuilder refs=new StringBuilder("[");
        for (int i=0;i<inputs;i++) {
            if (i>0) refs.append(',');
            refs.append("{\"fileId\":\"file-").append(i)
                    .append("\",\"version\":1,\"purpose\":\"REFERENCE\"}");
        }
        refs.append(']');
        return "{\"schemaVersion\":1,\"assignment\":{"+
                "\"workflowVersion\":2,\"businessAction\":\"assign_and_start\","+
                "\"expectedTaskVersion\":6,\"requirementRevision\":3,\"agentId\":\"agent-a\","+
                "\"requestedOperations\":[\"GENERATE_IMAGE\"],\"initialOperation\":\"GENERATE_IMAGE\","+
                "\"inputRefs\":"+refs+"},\"providerConsent\":{"+
                "\"consentId\":\"consent_1234567890abcdef1234567890abcdef\","+
                "\"expectedVersion\":\""+version+"\"}}";
    }
    private static JwtAuthenticationToken jwt(String owner,String client) {
        Jwt token=Jwt.withTokenValue("fixture").header("alg","none").claim("tenant_id","0")
                .claim("jiacn",owner).claim("client_id",client).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        JwtAuthenticationToken authentication=new JwtAuthenticationToken(token,List.of());
        authentication.setAuthenticated(true);return authentication;
    }
}
