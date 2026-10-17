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
