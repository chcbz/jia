package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskProviderCostConsentDTO;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTaskProviderCostConsentControllerTest {
    private AgentTaskProviderCostConsentService service;private MockMvc mvc;
    @BeforeEach void setUp(){service=mock(AgentTaskProviderCostConsentService.class);
        mvc=MockMvcBuilders.standaloneSetup(new AgentTaskProviderCostConsentController(service)).build();
        EsContext c=new EsContext();c.setTenantId("0");c.setClientId("client-a");c.setJiacn("owner-a");EsContextHolder.setContext(c);}
    @AfterEach void clear(){EsContextHolder.clearContext();}

    @Test void exactIssueReplayReceiptAndReadOnlyGetsUseNoStore() throws Exception {
        var receipt=receipt("ISSUED","1");
        when(service.issue(any(),eq("task-1"),eq("consent-key"),any())).thenReturn(
                new AgentTaskProviderCostConsentService.Result(receipt,false),
                new AgentTaskProviderCostConsentService.Result(receipt,true));
        when(service.get(any(),eq("task-1"),eq("consent-a"))).thenReturn(receipt);
        when(service.getByIdempotencyKey(any(),eq("task-1"),eq("consent-key"))).thenReturn(receipt);
        MvcResult created=mvc.perform(post("/agent/tasks/task-1/point-and-start-cost-consents")
                        .principal(jwt("owner-a","client-a")).header("Idempotency-Key","consent-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"))
                .andExpect(jsonPath("$.version").value("1")).andExpect(jsonPath("$.providerBinding.bindingEpoch").value("7"))
                .andReturn();assertFields(created);
        mvc.perform(post("/agent/tasks/task-1/point-and-start-cost-consents")
                        .principal(jwt("owner-a","client-a")).header("Idempotency-Key","consent-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isOk());
        mvc.perform(get("/agent/tasks/task-1/point-and-start-cost-consents/consent-a")
                        .principal(jwt("owner-a","client-a"))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"));
        mvc.perform(get("/agent/tasks/task-1/point-and-start-cost-consents/request")
                        .principal(jwt("owner-a","client-a")).header("Idempotency-Key","consent-key"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"));
        var command=org.mockito.ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskProviderCostConsentIssueDTO.class);
        verify(service,times(2)).issue(eq(new AgentTaskProviderCostConsentService.Scope("0","client-a","owner-a")),
                eq("task-1"),eq("consent-key"),command.capture());
        assertEquals(List.of("GENERATE_IMAGE"),command.getValue().getAssignment().getRequestedOperations());
        assertEquals("7",command.getValue().getProviderBinding().bindingEpoch());
    }

    @Test void strictUnknownDuplicateAuthorityAndUnsafeVersionsFailBeforeService() throws Exception {
        for(String invalid:List.of(body().replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"),
                body().replace("\"acknowledgement\"","\"authority\":true,\"acknowledgement\""),
                body().replace("\"inputRefs\":[]","\"costAuthorizationRef\":\"fake\",\"inputRefs\":[]"),
                body().replace("\"expectedTaskVersion\":5","\"expectedTaskVersion\":9007199254740992"),
                body()+" {}")) {
            mvc.perform(post("/agent/tasks/task-1/point-and-start-cost-consents")
                            .principal(jwt("owner-a","client-a")).header("Idempotency-Key","bad")
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        mvc.perform(post("/agent/tasks/task-1/point-and-start-cost-consents")
                        .principal(jwt("owner-a","client-a")).header("Idempotency-Key","one")
                        .header("Idempotency-Key","two").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/agent/tasks/task-1/point-and-start-cost-consents/request?owner=victim")
                        .principal(jwt("owner-a","client-a")).header("Idempotency-Key","key"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void revokeRequiresIndependentKeyAndCanonicalStringVersion() throws Exception {
        when(service.revoke(any(),eq("task-1"),eq("consent-a"),eq("revoke-key"),eq(1L)))
                .thenReturn(receipt("REVOKED","2"));
        mvc.perform(post("/agent/tasks/task-1/point-and-start-cost-consents/consent-a/revoke")
                        .principal(jwt("owner-a","client-a")).header("Idempotency-Key","revoke-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":\"1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REVOKED"));
        for(String bad:List.of("{\"expectedVersion\":1}","{\"expectedVersion\":\"01\"}",
                "{\"expectedVersion\":\"9007199254740992\"}")) {
            mvc.perform(post("/agent/tasks/task-1/point-and-start-cost-consents/consent-a/revoke")
                            .principal(jwt("owner-a","client-a")).header("Idempotency-Key","other")
                            .contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
        verify(service,times(1)).revoke(any(),anyString(),anyString(),anyString(),anyLong());
    }

    @Test void runtimeOrContextDriftCannotActAsOwnerAndForeignReadsStayGeneric() throws Exception {
        mvc.perform(get("/agent/tasks/task-1/point-and-start-cost-consents/consent-a"))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"private, no-store"));
        mvc.perform(get("/agent/tasks/task-1/point-and-start-cost-consents/consent-a")
                        .principal(jwt("owner-b","client-a"))).andExpect(status().isForbidden());
        when(service.get(any(),anyString(),anyString())).thenThrow(new AgentTaskProviderCostConsentService.Failure(
                AgentTaskProviderCostConsentService.Reason.NOT_FOUND));
        mvc.perform(get("/agent/tasks/task-1/point-and-start-cost-consents/foreign")
                        .principal(jwt("owner-a","client-a"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Provider consent is unavailable"));
    }

    private static String body(){return """
            {"schemaVersion":1,"assignmentIdempotencyKey":"assign-key","assignment":{
              "workflowVersion":2,"businessAction":"assign_and_start","expectedTaskVersion":5,
              "requirementRevision":2,"agentId":"agent-a","requestedOperations":["GENERATE_IMAGE"],
              "initialOperation":"GENERATE_IMAGE","inputRefs":[]},
             "providerBinding":{"bindingId":"binding-a","bindingEpoch":"7"},
             "acknowledgement":"UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT"}
            """;}
    private static AgentTaskProviderCostConsentDTO receipt(String state,String version){return new AgentTaskProviderCostConsentDTO(
            1,"consent-a","task-1","agent-a",state,version,"assign-key","a".repeat(64),"b".repeat(64),
            new AgentTaskProviderCostConsentDTO.ProviderBinding("binding-a","7"),"model-a","OWNER_EXTERNAL_ACCOUNT",
            "policy-r1","UNPRICED_EXTERNAL_ACCOUNT",1,"2000000000000");}
    private static void assertFields(MvcResult result)throws Exception{JsonNode root=new ObjectMapper().readTree(result.getResponse().getContentAsString());
        Set<String> names=new java.util.HashSet<>();root.propertyNames().forEach(names::add);
        assertEquals(Set.of("schemaVersion","consentId","taskId","targetAgentId","state","version",
                "assignmentIdempotencyKey","assignmentBaseHash","inputSnapshotDigest","providerBinding",
                "modelId","custody","operatorPolicyRevision","pricingMode","maxOutboundRequestAttempts","expiresAt"),names);}
    private static JwtAuthenticationToken jwt(String owner,String client){Jwt token=Jwt.withTokenValue("fixture").header("alg","none")
            .claim("tenant_id","0").claim("jiacn",owner).claim("client_id",client).issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600)).build();JwtAuthenticationToken auth=new JwtAuthenticationToken(token,List.of());auth.setAuthenticated(true);return auth;}
}
