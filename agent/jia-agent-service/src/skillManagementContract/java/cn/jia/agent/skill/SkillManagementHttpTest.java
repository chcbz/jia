package cn.jia.agent.skill;

import cn.jia.agent.api.SkillMarketplaceController;
import cn.jia.agent.api.SkillPackageController;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static cn.jia.agent.skill.SkillManagementCodec.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual Controller, byte[] conversion, correlation filter and common response advice.
 * Application calls are mocked here; SQL/state and money are tested by the separate owned-MySQL suite. */
class SkillManagementHttpTest {
    MockMvc mvc;
    SkillManagementService service;
    SkillMarketplaceService legacy;
    JwtAuthenticationToken auth;
    @BeforeEach void setup() {
        service=mock(SkillManagementService.class);legacy=mock(SkillMarketplaceService.class);
        var controller=new SkillMarketplaceController(legacy);controller.setManagement(service);
        List<HttpMessageConverter<?>> converters=new ArrayList<>();converters.add(new ByteArrayHttpMessageConverter());converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc=MockMvcBuilders.standaloneSetup(controller,new SkillPackageController(service)).setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice(),new ExceptionHandlerAdvice())
            .setMessageConverters(converters.toArray(HttpMessageConverter[]::new)).addFilters(new RequestIdFilter()).build();
        auth=new JwtAuthenticationToken(Jwt.withTokenValue("synthetic").claim("tenant_id", "0").header("alg","none").subject("buyer").claim("client_id","sm-client").claim("jiacn","owner").build(),List.of());
    }
    Map<String,Object> sample(String definition) throws Exception {
        for(var c:SkillManagementCodecTest.examples().get("cases"))if(c.get("valid").asBoolean()&&definition.equals(c.get("definition").asText()))
            return SkillManagementCodecTest.JSON.convertValue(c.get("value"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        throw new AssertionError("Missing locked fixture: "+definition);
    }
    @Test void completeQuoteEnvelopeAndIdHeaderArePreserved() throws Exception {
        var request=sample("QuoteRequest");var response=sample("QuoteResponse");String id=text(request,"requestId");
        when(service.quote(any(),eq("quote-key"),eq(object(request.get("data"))),eq(false))).thenReturn(object(response.get("data")));
        var result=mvc.perform(post("/agent/skill-orders/quotes").principal(auth).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id",id).header("Idempotency-Key","quote-key").content(bytes(request)))
            .andExpect(status().isOk()).andExpect(header().string("X-Request-Id",id)).andExpect(header().string("Cache-Control","private, no-store"))
            .andExpect(jsonPath("$.code").value("E0")).andReturn();
        validate("QuoteResponse",SkillManagementCodecTest.JSON.readValue(result.getResponse().getContentAsByteArray(),Map.class));verifyNoInteractions(legacy);
    }
    @Test void createRetryAndReconcileAre202RatherThanFalseInstallationSuccess() throws Exception {
        var response=object(sample("ChangeResponse").get("data"));
        when(service.create(any(),anyString(),anyString(),anyString(),any(),anyBoolean())).thenReturn(response);
        when(service.retry(any(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(response);
        when(service.reconcile(any(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn(response);
        for(String route:List.of("/agent/agt_demo/skill-changes","/agent/agt_demo/skill-changes/sc_demo/retry","/agent/agt_demo/skill-changes/sc_demo/reconcile")) {
            var request=sample(route.endsWith("retry")?"RetryRequest":route.endsWith("reconcile")?"ReconcileRequest":"CreateChangeRequest");
            var result=mvc.perform(post(route).principal(auth).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id",text(request,"requestId")).header("Idempotency-Key","write-key").content(bytes(request)))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","private, no-store")).andReturn();
            validate("ChangeResponse",SkillManagementCodecTest.JSON.readValue(result.getResponse().getContentAsByteArray(),Map.class));
        }
    }
    @Test void readsUseAclServiceAndRejectHiddenBodyAndQuery() throws Exception {
        when(service.skills(any(),eq("agt_demo"),eq(false))).thenReturn(object(sample("SkillListResponse").get("data")));
        mvc.perform(get("/agent/agt_demo/skills").principal(auth).header("X-Request-Id","read")).andExpect(status().isOk());
        mvc.perform(get("/agent/agt_demo/skills").principal(auth).param("tenantId","foreign")).andExpect(status().isBadRequest());
        mvc.perform(get("/agent/agt_demo/skills").principal(auth).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/agent/agt_demo/skills")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        when(service.skills(any(),eq("agt_demo"),eq(false))).thenThrow(new SkillMarketplaceException(403,"AGENT_FORBIDDEN"));
        mvc.perform(get("/agent/agt_demo/skills").principal(auth)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AGENT_FORBIDDEN"));
    }
    @Test void retiredPostCannotCallEitherPurchaseStateMachineButHistoryStillUsesAcl() throws Exception {
        mvc.perform(post("/agent/skill-orders").principal(auth).contentType(MediaType.APPLICATION_JSON).content("{not even a purchase}"))
            .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ENDPOINT_RETIRED"));
        verifyNoInteractions(service,legacy);
        when(legacy.order(any(),eq("other-order"))).thenThrow(new SkillMarketplaceException(403,"AGENT_FORBIDDEN"));
        mvc.perform(get("/agent/skill-orders/other-order").principal(auth)).andExpect(status().isForbidden());
    }
    @Test void packageRequiresTrustedRuntimeProofNotAnOrdinaryUserJwt() throws Exception {
        mvc.perform(get("/internal/agent/skill-installations/si_demo/package").principal(auth))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(header().string("Cache-Control","private, no-store"));
        verifyNoInteractions(service);
    }
    @Test void malformedOriginalJsonNeverReachesApplication() throws Exception {
        String good=json(sample("QuoteRequest"));
        for(String bad:List.of(good.replace("\"protocolVersion\":1","\"protocolVersion\":1.0"),good.replace("\"protocolVersion\":1","\"protocolVersion\":1,\"protocolVersion\":1"),good.replace("\"protocolVersion\":1","\"protocolVersion\":2")))
            mvc.perform(post("/agent/skill-orders/quotes").principal(auth).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id",text(sample("QuoteRequest"),"requestId")).header("Idempotency-Key","key").content(bad))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/agent/skill-orders/quotes").principal(auth).contentType(MediaType.APPLICATION_JSON).header("X-Request-Id","different").header("Idempotency-Key","key").content(good))
            .andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void allContractBusinessErrorsKeepTheirActualHttpStatus() throws Exception {
        var cases=SkillManagementCodecTest.JSON.readTree(SkillManagementCodecTest.resource("management-contract-v1.behavior.json")).get("errorStatus");
        for(var fields=cases.fields();fields.hasNext();) {
            var entry=fields.next();doThrow(new SkillMarketplaceException(entry.getValue().asInt(),entry.getKey()))
                .when(service).skills(any(),anyString(),anyBoolean());
            mvc.perform(get("/agent/agt_demo/skills").principal(auth)).andExpect(status().is(entry.getValue().asInt())).andExpect(jsonPath("$.code").value(entry.getKey()))
                .andExpect(header().string("Cache-Control","private, no-store"));
        }
    }
}
