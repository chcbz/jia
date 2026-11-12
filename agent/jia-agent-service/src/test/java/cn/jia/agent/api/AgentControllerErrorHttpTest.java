package cn.jia.agent.api;

import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.core.security.SensitiveResponseBodyAdvice;
import cn.jia.core.security.SensitiveResponseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Actual GET routing, exception mapping, response advice and serialization; service is mocked. */
class AgentControllerErrorHttpTest {
    private static final String REQUEST_ID = "agent-error-regression";
    private static final String BUSY_MESSAGE = "该好汉仍有未结束的任务，请先处理任务后再移除";
    private final AgentService agents = mock(AgentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
                    agents, mock(AbilityEvaluationService.class)))
            .addFilters(new RequestIdFilter())
            .setControllerAdvice(new ApiEnvelopeAdvice(),
                    new SensitiveResponseBodyAdvice(new SensitiveResponseProperties()))
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .build();

    @Test
    void successfulReadKeepsExplicitNullData() throws Exception {
        JsonNode body = request(200, "E0");
        assertEquals("ok", body.path("msg").asText());
        verify(agents).get("agent-a");
    }

    @Test
    void busyFailureIsConflictNotSuccessWithAnErrorCode() throws Exception {
        doThrow(new AgentBizException("AGENT_BUSY", BUSY_MESSAGE))
                .when(agents).get("agent-a");
        assertEquals(BUSY_MESSAGE, request(409, "AGENT_BUSY").path("msg").asText());
        verify(agents, times(1)).get("agent-a");
    }

    @ParameterizedTest
    @CsvSource({"TOKEN_INVALID,401", "AGENT_FORBIDDEN,403", "AGENT_NOT_FOUND,404",
            "TASK_NOT_FOUND,404", "AGENT_OFFLINE,409", "PERSONA_BOUND,409",
            "PERSONA_NOT_BINDABLE,409", "TASK_STATUS_INVALID,409", "AGENT_ABILITY_MISMATCH,422",
            "AGENT_ERROR,500", "UNKNOWN_AGENT_FAILURE,500"})
    void businessFailuresUseNonSuccessHttpStatus(String code, int status) throws Exception {
        doThrow(new AgentBizException(code, "internal detail"))
                .when(agents).get("agent-a");
        JsonNode body = request(status, code);
        if (status == 500) assertFalse(body.path("msg").asText().contains("internal detail"));
    }

    @Test
    void invalidInputIsBadRequest() throws Exception {
        doThrow(new IllegalArgumentException("internal detail"))
                .when(agents).get("agent-a");
        assertEquals("请求参数不正确", request(400, "BAD_REQUEST").path("msg").asText());
    }

    @Test
    void unexpectedFailureIsServerErrorWithoutInternalDetails() throws Exception {
        doThrow(new IllegalStateException("internal detail"))
                .when(agents).get("agent-a");
        assertEquals("好汉操作失败，请稍后重试", request(500, "AGENT_ERROR").path("msg").asText());
    }

    private JsonNode request(int status, String code) throws Exception {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "none")
                .claim("jiacn", "owner").claim("client_id", "client").build();
        var response = mvc.perform(get("/agent/agent-a")
                        .principal(new JwtAuthenticationToken(jwt, List.of()))
                        .header(RequestIdFilter.HEADER_NAME, REQUEST_ID))
                .andReturn().getResponse();
        assertEquals(status, response.getStatus());
        assertEquals(REQUEST_ID, response.getHeader(RequestIdFilter.HEADER_NAME));
        if (status != 200) assertEquals("private, no-store", response.getHeader("Cache-Control"));
        JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsByteArray());
        assertEquals(5, body.size());
        assertEquals(1, body.path("protocolVersion").asInt());
        assertEquals(REQUEST_ID, body.path("requestId").asText());
        assertEquals(code, body.path("code").asText());
        assertTrue(body.path("msg").isString());
        assertTrue(body.has("data"));
        assertTrue(body.path("data").isNull());
        return body;
    }
}
