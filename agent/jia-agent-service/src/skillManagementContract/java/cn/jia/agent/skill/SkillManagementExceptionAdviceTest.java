package cn.jia.agent.skill;

import cn.jia.agent.api.SkillApiExceptionAdvice;
import cn.jia.agent.api.SkillApiWebConfiguration;
import cn.jia.agent.api.SkillMarketplaceController;
import cn.jia.agent.api.SkillPackageController;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.config.ApiEnvelopeJsonConfig;
import cn.jia.core.config.ApiRequestEnvelopeAdvice;
import cn.jia.core.config.ExceptionHandlerAdvice;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.economy.exception.EconomyPostingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC exception dispatch, advice precedence, envelope and cache policy. No production DB or Provider. */
class SkillManagementExceptionAdviceTest {
    private static final String REQUEST_ID = "skill-error-regression";
    private SkillManagementService management;
    private MockMvc mvc;
    private JwtAuthenticationToken auth;

    @BeforeEach
    void setup() {
        management = mock(SkillManagementService.class);
        var controller = new SkillMarketplaceController(mock(SkillMarketplaceService.class));
        controller.setManagement(management);
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        converters.add(new ByteArrayHttpMessageConverter());
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc = MockMvcBuilders.standaloneSetup(controller, new SkillPackageController(management), new UnrelatedController())
                .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice(),
                        new ExceptionHandlerAdvice(), new SkillApiExceptionAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).addInterceptors(new SkillApiWebConfiguration()).build();
        auth = new JwtAuthenticationToken(Jwt.withTokenValue("synthetic").header("alg", "none")
                .subject("buyer").claim("client_id", "sm-client").claim("jiacn", "owner").build(), List.of());
    }

    record FailureCase(RuntimeException error, int status, String code) { }

    static Stream<FailureCase> errors() {
        var cases = new ArrayList<FailureCase>(List.of(
                new FailureCase(new AgentBizException("AGENT_FORBIDDEN", "private ownership details"), 403, "AGENT_FORBIDDEN"),
                new FailureCase(new AgentBizException("AGENT_NOT_FOUND", "private details"), 404, "AGENT_NOT_FOUND"),
                new FailureCase(new AgentBizException("AGENT_ERROR", "private details"), 500, "INTERNAL_ERROR"),
                new FailureCase(new SkillMarketplaceException(409, "AGENT_VERSION_CONFLICT"), 409, "SKILL_STATE_CONFLICT"),
                new FailureCase(new SkillMarketplaceException(403, "SKILL_MARKETPLACE_DISABLED"), 503, "SKILL_STATE_UNAVAILABLE"),
                new FailureCase(new SkillMarketplaceException(500, "SKILL_PACKAGE_UNAVAILABLE"), 503, "SKILL_STATE_UNAVAILABLE"),
                new FailureCase(new SkillMarketplaceException(500, "SKILL_PACKAGE_PREPARE_IN_TRANSACTION"), 503, "SKILL_STATE_UNAVAILABLE"),
                new FailureCase(new HostingRentApplicationException(401, "HOSTING_AUTH"), 401, "UNAUTHENTICATED"),
                new FailureCase(new HostingRentApplicationException(400, "HOSTING_INPUT"), 400, "BAD_REQUEST"),
                new FailureCase(new HostingRentApplicationException(403, "HOSTING_OWNER_UNPROVEN"), 403, "AGENT_FORBIDDEN"),
                new FailureCase(new HostingRentApplicationException(409, "HOSTING_STATE"), 409, "AGENT_FORBIDDEN"),
                new FailureCase(new DataAccessResourceFailureException("private database details"), 503, "SKILL_STATE_UNAVAILABLE"),
                new FailureCase(new CannotCreateTransactionException("private database details"), 503, "SKILL_STATE_UNAVAILABLE"),
                new FailureCase(new IllegalArgumentException("AGENT_RUNTIME_UNAUTHENTICATED"), 401, "UNAUTHENTICATED"),
                new FailureCase(new IllegalArgumentException("private invalid input"), 400, "BAD_REQUEST"),
                new FailureCase(new HttpMessageNotReadableException("private parse details", new MockHttpInputMessage(new byte[0])), 400, "BAD_REQUEST"),
                // Existing common fallback owns unknown failures (its code is E999, not a skill-specific code).
                new FailureCase(new IllegalStateException("private diagnostic only"), 500, "E999")));
        for (var reason : EconomyPostingException.Reason.values()) {
            var error = new EconomyPostingException(reason, "private ledger details");
            cases.add(switch (reason) {
                case INSUFFICIENT_FUNDS -> new FailureCase(error, 422, "INSUFFICIENT_FUNDS");
                case IDEMPOTENCY_CONFLICT -> new FailureCase(error, 409, "IDEMPOTENCY_CONFLICT");
                case FEATURE_DISABLED -> new FailureCase(error, 403, "AGENT_FORBIDDEN");
                case ACCOUNT_NOT_FOUND, ACCOUNT_NOT_ACTIVE, JOURNAL_ID_CONFLICT, CONCURRENCY_CONFLICT, ESCROW_CONFLICT ->
                        new FailureCase(error, 409, "SKILL_STATE_CONFLICT");
                // Includes AMOUNT_RANGE_EXCEEDED: preserve the old adapter's final result, not its intermediate status.
                default -> new FailureCase(error, 500, "INTERNAL_ERROR");
            });
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("errors")
    void typedFailuresPreserveStatusEnvelopeCorrelationAndPrivacy(FailureCase test) throws Exception {
        when(management.skills(any(), eq("agt_demo"), eq(false))).thenThrow(test.error());
        mvc.perform(get("/agent/agt_demo/skills").principal(auth).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().is(test.status())).andExpect(jsonPath("$.code").value(test.code()))
                .andExpect(jsonPath("$.protocolVersion").value(1)).andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data").isEmpty()).andExpect(content().string(not(containsString("private"))))
                .andExpect(header().string("X-Request-Id", REQUEST_ID))
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verify(management, times(1)).skills(any(), eq("agt_demo"), eq(false));
    }

    @Test
    void ownedAgentReadStaysSuccessful() throws Exception {
        when(management.skills(any(), eq("agt_demo"), eq(false))).thenReturn(java.util.Map.of("items", List.of()));
        mvc.perform(get("/agent/agt_demo/skills").principal(auth).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.items").isArray()).andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void packageProofStillRejectsUserJwtThroughTypedAdvice() throws Exception {
        mvc.perform(get("/internal/agent/skill-installations/si_demo/package").principal(auth).header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID)).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().string("Cache-Control", "private, no-store"));
        verifyNoInteractions(management);
    }

    @Test
    void newAdviceAndCachePolicyDoNotApplyToUnrelatedControllers() throws Exception {
        mvc.perform(get("/unrelated/error").header("X-Request-Id", REQUEST_ID))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("E999"))
                .andExpect(header().doesNotExist("Cache-Control"));
    }

    @Test
    void springDiscoversScopedAdviceAndResponsePolicyWithoutManualMvcRegistration() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(DiscoveredMvc.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(SkillApiExceptionAdvice.class).size());
            var service = context.getBean(SkillManagementService.class);
            var actualMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new RequestIdFilter()).build();
            when(service.skills(any(), anyString(), anyBoolean())).thenThrow(new AgentBizException("AGENT_FORBIDDEN", "private owner"));
            actualMvc.perform(get("/agent/foreign/skills").principal(auth).header("X-Request-Id", REQUEST_ID))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AGENT_FORBIDDEN"))
                    .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                    .andExpect(header().string("Cache-Control", "private, no-store"));
            when(service.skills(any(), anyString(), anyBoolean())).thenThrow(new IllegalStateException("private failure"));
            actualMvc.perform(get("/agent/agt_demo/skills").principal(auth).header("X-Request-Id", REQUEST_ID))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("E999"))
                    .andExpect(header().string("Cache-Control", "private, no-store"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({SkillMarketplaceController.class, ApiEnvelopeAdvice.class, ApiRequestEnvelopeAdvice.class, ExceptionHandlerAdvice.class})
    @ComponentScan(basePackageClasses = SkillApiExceptionAdvice.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {SkillApiExceptionAdvice.class, SkillApiWebConfiguration.class}))
    static class DiscoveredMvc implements WebMvcConfigurer {
        @Bean SkillManagementService management() { return mock(SkillManagementService.class); }
        @Bean SkillMarketplaceService marketplace() { return mock(SkillMarketplaceService.class); }
        @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        }
    }

    @RestController
    static class UnrelatedController {
        @GetMapping("/unrelated/error")
        public Object error() { throw new AgentBizException("AGENT_FORBIDDEN", "outside skill API"); }
    }
}
