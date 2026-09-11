package cn.jia.agent.config;

import cn.jia.agent.api.OutputUploadController;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.OutputUploadService;
import cn.jia.agent.output.dto.OutputUploadDTO;
import cn.jia.core.config.SpringContextHolder;
import cn.jia.oauth.config.ResourceServerConfig;
import cn.jia.user.config.DefaultSecurityConfig;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.service.PermsService;
import cn.jia.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OutputUploadSecurityCoexistenceTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of(
                "agent.output-delivery.enabled",true,
                "oauth.resource.uris[0]","/agent/outputs")));
        new SpringContextHolder().setApplicationContext(context);
        context.register(TestConfig.class);context.setServletContext(new org.springframework.mock.web.MockServletContext());context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach void close(){if(context!=null)context.close();SpringContextHolder.cleanApplicationContext();SecurityContextHolder.clearContext();}

    @Test void productionControllerReturnsAcceptedReceiptThroughOutputTicketChain()throws Exception{
        mvc.perform(post("/agent/output-uploads/upload-1/complete")
                        .header("Authorization","Bearer active-ticket-value-that-is-long-enough-000000")
                        .header("Idempotency-Key","complete-http-key-01"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("E0")).andExpect(jsonPath("$.data.state").value("VERIFYING"))
                .andExpect(jsonPath("$.status").doesNotExist()).andExpect(jsonPath("$.msg").doesNotExist())
                .andExpect(jsonPath("$.data.uploadUrl").doesNotExist()).andExpect(jsonPath("$.data.errorCode").doesNotExist());
    }

    @Test void authenticatedMissingHeaderAndMissingBodyAreBadRequests()throws Exception{
        String ticket="Bearer active-ticket-value-that-is-long-enough-000000";
        mvc.perform(post("/agent/output-uploads/upload-1/complete").header("Authorization",ticket).header("X-Request-ID","wire-bad-1"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("X-Request-ID","wire-bad-1"))
                .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("Invalid output request"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.requestId").value("wire-bad-1"))
                .andExpect(jsonPath("$.status").doesNotExist()).andExpect(jsonPath("$.msg").doesNotExist());
        mvc.perform(post("/agent/output-uploads").header("Authorization",ticket).header("Idempotency-Key","create-http-key-0001").contentType("application/json"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"))
                .andExpect(jsonPath("$.message").value("Invalid output request"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.requestId").isString());
    }

    @Test void authenticatedSessionAndUserBearerCannotReplaceRunTicket()throws Exception{
        MockHttpSession session=authenticatedSession();
        mvc.perform(post("/agent/output-uploads/upload-1/complete").session(session).header("Idempotency-Key","complete-http-key-01").header("X-Request-ID","wire-auth-1"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("X-Request-ID","wire-auth-1"))
                .andExpect(jsonPath("$.code").value("OUTPUT_AUTH_UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Output access is unavailable"))
                .andExpect(jsonPath("$.retryable").value(false)).andExpect(jsonPath("$.requestId").value("wire-auth-1"))
                .andExpect(jsonPath("$.status").doesNotExist()).andExpect(jsonPath("$.msg").doesNotExist());
        mvc.perform(post("/agent/output-uploads/upload-1/complete").session(session).header("Authorization","Bearer user-jwt-token-value-that-is-not-run-ticket").header("Idempotency-Key","complete-http-key-01")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OUTPUT_AUTH_UNAUTHORIZED")).andExpect(jsonPath("$.requestId").isString());
    }

    @Test void actualResourceAndFallbackChainsRemainOrderedAndIsolated()throws Exception{
        MockHttpSession session=authenticatedSession();
        mvc.perform(get("/agent/outputs").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/session-only").session(session)).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(4,context.getBean(FilterChainProxy.class).getFilterChains().size());
    }

    private static MockHttpSession authenticatedSession(){
        var security=SecurityContextHolder.createEmptyContext();security.setAuthentication(new UsernamePasswordAuthenticationToken("user","n/a",List.of()));
        MockHttpSession session=new MockHttpSession();session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,security);return session;
    }

    @Configuration(proxyBeanMethods=false)@EnableWebMvc@EnableConfigurationProperties
    @Import({OutputUploadSecurityConfiguration.class,ResourceServerConfig.class,DefaultSecurityConfig.class,OutputUploadController.class})
    static class TestConfig{
        @Bean SpringContextHolder springContextHolder(){return new SpringContextHolder();}
        @Bean OutputRunAuthorizationService authorization(){return new StubAuthorization();}
        @Bean OutputUploadService outputUploadService(){OutputUploadService service=mock(OutputUploadService.class);when(service.complete(anyString(),anyString(),anyString())).thenReturn(new OutputUploadDTO("upload-1","VERIFYING",null,"9999999999999","object-1",null));return service;}
        @Bean UserService userService(){return mock(UserService.class);}
        @Bean PermsService permsService(){return mock(PermsService.class);}
        @Bean AccountSecurityService accountSecurityService(){return mock(AccountSecurityService.class);}
        @Bean Routes routes(){return new Routes();}
    }

    @RestController static class Routes{
        @GetMapping("/agent/outputs")String outputs(){return "user";}
        @GetMapping("/session-only")String session(){return "session";}
    }

    static final class StubAuthorization extends OutputUploadSecurityConfigurationTest.StubAuthorization{
        @Override public OutputTicketAuthorization authorizeTicket(String raw,String op,boolean replay){if(raw.startsWith("active-ticket"))return new OutputTicketAuthorization("owner","client","run","TASK","task","agent","7","runtime",List.of("upload","publish","status"),System.currentTimeMillis()+10_000,"ACTIVE");throw new OutputAuthorizationException("OUTPUT_AUTH_FORBIDDEN","denied");}
    }
}
