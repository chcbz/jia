package cn.jia.chat.api;

import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.chat.service.*;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.context.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentProfileCapabilitiesControllerTest {
    final AgentProfileCapabilitiesService profiles = mock(AgentProfileCapabilitiesService.class);
    MockMvc mvc;
    JwtAuthenticationToken login;
    @BeforeEach void setup() {
        var jwt = Jwt.withTokenValue("fixture").claim("tenant_id", "0").header("alg", "RS256").subject("user").claim("jiacn", "owner").claim("client_id", "client").build();
        login = new JwtAuthenticationToken(jwt, List.of());
        var context = new EsContext(); context.setTenantId("0"); context.setClientId("client"); context.setJiacn("owner"); EsContextHolder.setContext(context);
        mvc = MockMvcBuilders.standaloneSetup(new AgentProfileCapabilitiesController(profiles, new TenantScopeResolver()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).setControllerAdvice(new ApiEnvelopeAdvice())
                .addFilters(new RequestIdFilter()).build();
        when(profiles.read(any(), eq("agent"))).thenReturn(new AgentProfileCapabilitiesService.ProfileCapabilities("agent", 10000,
                List.of("内容管理"), null, new AgentProfileCapabilitiesService.SkillDirectory("AVAILABLE", null, null, List.of())));
    }
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }
    @Test void successUsesOneClosedEnvelopeRealStatusAndPrivateNoStore() throws Exception {
        var result = mvc.perform(get("/agent/agent/profile-capabilities").principal(login).header("X-Request-Id", "profile-test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Request-Id", "profile-test")).andExpect(jsonPath("$.requestId").value("profile-test"))
                .andExpect(jsonPath("$.protocolVersion").value(1)).andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.status").doesNotExist()).andExpect(jsonPath("$.data.abilities[0]").value("内容管理"))
                .andExpect(jsonPath("$.data.skills.items").isEmpty())
                .andExpect(jsonPath("$.data.lastSeenAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.skills.catalogRevision").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.skills.reasonCode").value(org.hamcrest.Matchers.nullValue())).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(result.getResponse().getContentAsString().contains("metadataJson"));
    }
    @Test void missingUserCredentialIs401AndMismatchDoesNotRevealExistence() throws Exception {
        mvc.perform(get("/agent/agent/profile-capabilities")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        EsContextHolder.getContext().setClientId("other");
        mvc.perform(get("/agent/agent/profile-capabilities").principal(login)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AGENT_PROFILE_NOT_FOUND"));
        verifyNoInteractions(profiles);
    }
    @Test void contextOwnerTenantAndNonUserCredentialsDoNotEnumerate() throws Exception {
        for (String field : List.of("owner", "tenant", "client")) {
            EsContextHolder.getContext().setJiacn("owner");
            EsContextHolder.getContext().setClientId("client");
            EsContextHolder.getContext().setTenantId("0");
            if (field.equals("owner")) EsContextHolder.getContext().setJiacn("other");
            if (field.equals("tenant")) EsContextHolder.getContext().setTenantId("other");
            if (field.equals("client")) EsContextHolder.getContext().setClientId("other");
            mvc.perform(get("/agent/agent/profile-capabilities").principal(login))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AGENT_PROFILE_NOT_FOUND"));
        }
        var nonUser = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("api-key", "fixture", List.of());
        mvc.perform(get("/agent/agent/profile-capabilities").principal(nonUser)).andExpect(status().isUnauthorized());
        verifyNoInteractions(profiles);
    }
    @Test void queryBodyTransferEncodingAndBadIdAreRejected() throws Exception {
        for (var request : List.of(get("/agent/agent/profile-capabilities").queryParam("owner", "other"),
                get("/agent/agent/profile-capabilities").content("{}"), get("/agent/agent/profile-capabilities").header("Transfer-Encoding", "chunked"),
                get("/agent/ agent/profile-capabilities")))
            mvc.perform(request.principal(login)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(profiles);
    }
    @Test void ownerRejectionAgentRejectionAndInfrastructureHaveDistinctSafeStatuses() throws Exception {
        doThrow(new HostingRentApplicationException(403, "private owner detail")).when(profiles).read(any(), any());
        mvc.perform(get("/agent/agent/profile-capabilities").principal(login)).andExpect(status().isNotFound());
        doThrow(new AgentServiceImpl.AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN, "secret")).when(profiles).read(any(), any());
        mvc.perform(get("/agent/agent/profile-capabilities").principal(login)).andExpect(status().isNotFound());
        doThrow(new IllegalStateException("secret database path")).when(profiles).read(any(), any());
        mvc.perform(get("/agent/agent/profile-capabilities").principal(login)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_PROFILE_UNAVAILABLE")).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test void realResourceFilterChainRejectsMissingAndInvalidBearerBeforeController() throws Exception {
        var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.register(cn.jia.oauth.config.ResourceServerConfig.class, ResourceChainFixtures.class);
        try {
            context.refresh();
            var filters = context.getBean(org.springframework.security.web.FilterChainProxy.class);
            var secured = MockMvcBuilders.standaloneSetup(new AgentProfileCapabilitiesController(profiles, new TenantScopeResolver()))
                    .setControllerAdvice(new ApiEnvelopeAdvice()).addFilters(new RequestIdFilter(), filters).build();
            secured.perform(get("/agent/agent/profile-capabilities")).andExpect(status().isUnauthorized());
            secured.perform(get("/agent/agent/profile-capabilities").header("Authorization", "Bearer invalid-fixture"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(profiles);
        } finally { context.close(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class ResourceChainFixtures {
        @org.springframework.context.annotation.Bean static org.springframework.beans.factory.config.BeanPostProcessor profileResourceUris() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                    if ("oauthResourceProperties".equals(name)) org.springframework.test.util.ReflectionTestUtils.setField(bean, "uris", List.of("/agent/**"));
                    return bean;
                }
            };
        }
        @org.springframework.context.annotation.Bean cn.jia.user.security.AccountSecurityService accounts() { return mock(cn.jia.user.security.AccountSecurityService.class); }
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary org.springframework.security.oauth2.jwt.JwtDecoder profileTestDecoder() {
            return token -> { throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid test credential"); };
        }
    }
}
