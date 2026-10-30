package cn.jia.agent.api;

import cn.jia.agent.service.recommendation.AssigneeRecommendationService;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.context.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static cn.jia.agent.entity.AssigneeRecommendation.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AssigneeRecommendationControllerTest {
    final AssigneeRecommendationService service = mock(AssigneeRecommendationService.class);
    MockMvc mvc;
    JwtAuthenticationToken login;
    static final String BODY = "{\"protocolVersion\":1,\"requestId\":\"recommend-test\",\"data\":{\"expectedTaskVersion\":\"7\"}}";
    @BeforeEach void setup() {
        login = new JwtAuthenticationToken(Jwt.withTokenValue("test").claim("tenant_id", "0").header("alg", "none")
                .claim("sub", "user").claim("jiacn", "owner").claim("client_id", "client").build(), List.of());
        var context = new EsContext(); context.setTenantId("0"); context.setClientId("client"); context.setJiacn("owner"); EsContextHolder.setContext(context);
        mvc = MockMvcBuilders.standaloneSetup(new AssigneeRecommendationController(service))
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).setControllerAdvice(new ApiEnvelopeAdvice())
                .addFilters(new RequestIdFilter()).build();
        when(service.recommend(any(), eq("task"), any())).thenReturn(new Result("task", "7", 1000, "NO_MATCH", "没有匹配证据", List.of(), List.of(), true));
    }
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }
    @Test void sameEndpointReturnsNewObjectInOneEnvelopeWithoutLegacyScores() throws Exception {
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY)
                .header("X-Request-Id", "recommend-test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.protocolVersion").value(1)).andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.state").value("NO_MATCH")).andExpect(jsonPath("$.data.taskVersion").value("7"))
                .andExpect(jsonPath("$.data.recommendations").isEmpty()).andExpect(jsonPath("$.data.score").doesNotExist());
    }
    @Test void missingIdentityAndMismatchedContextNeverInvokeModelService() throws Exception {
        mvc.perform(post("/agent/tasks/task/recommend").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        EsContextHolder.getContext().setJiacn("other");
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void unauthenticatedJwtNeverInvokesRecommendation() throws Exception {
        var unauthenticated = new JwtAuthenticationToken(login.getToken());
        mvc.perform(post("/agent/tasks/task/recommend").principal(unauthenticated).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void malformedJsonIsBadRequestNotProviderFailure() throws Exception {
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(service);
    }
    @Test void modelFailureUsesRealFailureNotEmptySuccessfulArray() throws Exception {
        when(service.recommend(any(), anyString(), any())).thenThrow(new Failure(503, "RECOMMENDATION_UNAVAILABLE"));
        mvc.perform(post("/agent/tasks/task/recommend").principal(login).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"));
    }
}
