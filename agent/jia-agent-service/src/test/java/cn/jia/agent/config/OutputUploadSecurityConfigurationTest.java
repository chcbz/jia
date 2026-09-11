package cn.jia.agent.config;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OutputUploadSecurityConfigurationTest {
    private AnnotationConfigWebApplicationContext context;private MockMvc mvc;
    @BeforeEach void setup(){context=new AnnotationConfigWebApplicationContext();context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",java.util.Map.of("agent.output-delivery.enabled",true)));context.register(TestConfig.class);context.setServletContext(new org.springframework.mock.web.MockServletContext());context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();}
    @AfterEach void close(){context.close();}
    @Test void exactUploadRoutesRejectCookiesUserBearerAndMissingTicket()throws Exception{mvc.perform(post("/agent/output-uploads")).andExpect(status().isUnauthorized());mvc.perform(post("/agent/output-uploads").header("Authorization","Bearer user-jwt-token-value-that-is-not-run-ticket")).andExpect(status().isUnauthorized());mvc.perform(put("/agent/output-uploads/u/content").header("Authorization","Bearer terminal-ticket-value-that-is-long-enough-0000")).andExpect(status().isForbidden());}
    @Test void validTicketPassesExactMethodsAndOutputUserRouteIsNotCaptured()throws Exception{String active="Bearer active-ticket-value-that-is-long-enough-000000";mvc.perform(post("/agent/output-uploads").header("Authorization",active)).andExpect(status().isOk());mvc.perform(put("/agent/output-uploads/u/content").header("Authorization",active)).andExpect(status().isOk());mvc.perform(get("/agent/output-uploads/u").header("Authorization",active)).andExpect(status().isOk());mvc.perform(get("/agent/outputs")).andExpect(status().isOk());}
    @Test void terminalStatusTicketCanReachReceiptPostsButCannotWriteBytes()throws Exception{String terminal="Bearer terminal-ticket-value-that-is-long-enough-0000";mvc.perform(get("/agent/output-uploads/u").header("Authorization",terminal)).andExpect(status().isOk());mvc.perform(post("/agent/output-uploads/u/complete").header("Authorization",terminal)).andExpect(status().isOk());mvc.perform(put("/agent/output-uploads/u/content").header("Authorization",terminal)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUTPUT_AUTH_FORBIDDEN")).andExpect(jsonPath("$.retryable").value(false));}
    @Test void expectedAuthorizationDenialAndUnexpectedInfrastructureFailureAreSeparated()throws Exception{
        mvc.perform(get("/agent/output-uploads/u").header("Authorization","Bearer acl-ticket-value-that-is-long-enough-000000").header("X-Request-ID","acl-request-1"))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Request-ID","acl-request-1"))
                .andExpect(jsonPath("$.code").value("OUTPUT_AUTH_FORBIDDEN")).andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.requestId").value("acl-request-1"));
        mvc.perform(get("/agent/output-uploads/u").header("Authorization","Bearer infra-ticket-value-that-is-long-enough-0000").header("X-Request-ID","infra-request-1"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Request-ID","infra-request-1"))
                .andExpect(jsonPath("$.code").value("OUTPUT_AUTH_UNAVAILABLE")).andExpect(jsonPath("$.message").value("Output authorization unavailable"))
                .andExpect(jsonPath("$.retryable").value(true)).andExpect(jsonPath("$.requestId").value("infra-request-1"));
    }

    @Configuration(proxyBeanMethods=false)@EnableWebSecurity@EnableWebMvc@Import(OutputUploadSecurityConfiguration.class)
    static class TestConfig{
        @Bean OutputRunAuthorizationService authorization(){return new StubAuthorization();}
        @Bean Routes routes(){return new Routes();}
    }
    @RestController static class Routes{
        @PostMapping("/agent/output-uploads")String create(){return "ok";}
        @PutMapping("/agent/output-uploads/{id}/content")String put(){return "ok";}
        @PostMapping("/agent/output-uploads/{id}/complete")String complete(){return "ok";}
        @GetMapping("/agent/output-uploads/{id}")String status(){return "ok";}
        @GetMapping("/agent/outputs")String user(){return "ok";}
    }
    static class StubAuthorization implements OutputRunAuthorizationService{
        @Override public java.util.Optional<cn.jia.agent.output.dto.OutputContextDTO> createOrRecoverRun(cn.jia.agent.output.OutputRunRequest r){throw new UnsupportedOperationException();}
        @Override public java.util.Map<String,cn.jia.agent.output.dto.OutputContextDTO> createOrRecoverRuns(List<cn.jia.agent.output.OutputRunRequest> r,List<String> i){throw new UnsupportedOperationException();}
        @Override public String requireFreshDispatchRuntime(String t,String c,String p,String r){throw new UnsupportedOperationException();}
        @Override public cn.jia.agent.output.dto.OutputAuthReceiptDTO issueTicket(String t,String c,String p,String runtime,String m,String r){throw new UnsupportedOperationException();}
        @Override public OutputTicketAuthorization authorizeTicket(String raw,String op,boolean replay){if(raw.startsWith("active-ticket"))return ticket("ACTIVE",List.of("upload","publish","status"));if(raw.startsWith("terminal-ticket"))return ticket("CLOSED",List.of("status"));if(raw.startsWith("acl-ticket"))throw new OutputAuthorizationException("OUTPUT_AUTH_FORBIDDEN","denied");if(raw.startsWith("infra-ticket"))throw new IllegalStateException("database unavailable");throw new OutputAuthorizationException("OUTPUT_AUTH_UNAUTHORIZED","denied");}
        @Override public boolean authorizePersistedMutation(String t,String c,String r,String b){return true;}
        private OutputTicketAuthorization ticket(String state,List<String> ops){return new OutputTicketAuthorization("owner","client","run","TASK","task","agent","7","runtime",ops,System.currentTimeMillis()+10000,state);}
    }
}
