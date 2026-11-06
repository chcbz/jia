package cn.jia.chat.service.usage;

import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.chat.api.ChatUsageCostController;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.economy.usage.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.function.Function;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual Q/R codecs and controller HTTP dispatch; session fencing is tested at its owning boundary. */
class ChatUsageCostWireTest {
    private final ChatUsageCostRecorder recorder=mock(ChatUsageCostRecorder.class);
    private final AuthenticatedRuntimeOperation authentication=mock(AuthenticatedRuntimeOperation.class);
    private final Authentication principal=mock(Authentication.class);
    private final AuthenticatedRuntimeOperation.Scope scope=new AuthenticatedRuntimeOperation.Scope("0","client","executor","agent");
    private MockMvc mvc;
    @BeforeEach void setup() {
        when(authentication.withAuthenticatedRuntime(eq(principal),any())).thenAnswer(i->
                ((Function<AuthenticatedRuntimeOperation.Scope, ?>)i.getArgument(1)).apply(scope));
        var converters=new ArrayList<HttpMessageConverter<?>>();
        converters.add(new StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc=MockMvcBuilders.standaloneSetup(new ChatUsageCostController(recorder,authentication))
                .setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice(),new ExceptionHandlerAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new)).addFilters(new RequestIdFilter()).build();
    }
    private org.springframework.test.web.servlet.ResultActions call(String data) throws Exception {
        return mvc.perform(post("/internal/agent/chat/dispatches/dispatch/usage").principal(principal)
                .contentType(MediaType.APPLICATION_JSON).header("X-Request-Id","usage-test")
                .content("{\"protocolVersion\":1,\"requestId\":\"usage-test\",\"data\":"+data+"}"));
    }
    @Test void nativeEvidenceJsonAndDecimalAcknowledgementCrossTheCommonWire() throws Exception {
        when(recorder.record(eq(scope),eq("dispatch"),any())).thenReturn(
                new UsageCostService.Receipt("a".repeat(64),2,TokenUsageCost.Status.PENDING_USAGE,null,false));
        call("{\"phase\":\"TERMINAL\",\"requestedModel\":\"requested-only\",\"usageEvidence\":{\"status\":\"UNVERIFIED\",\"last\":{\"inputTokens\":12}}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.protocolVersion").value(1))
                .andExpect(jsonPath("$.code").value("E0")).andExpect(jsonPath("$.requestId").value("usage-test"))
                .andExpect(jsonPath("$.data.dispatchId").value("dispatch")).andExpect(jsonPath("$.data.revision").value("2"))
                .andExpect(jsonPath("$.data.status").value("PENDING_USAGE")).andExpect(jsonPath("$.data.costYuan").isEmpty());
        verify(recorder).record(eq(scope),eq("dispatch"),argThat(e->e.phase()==ChatUsageCostRecorder.Phase.TERMINAL
                && e.usageEvidenceJson().contains("inputTokens") && "requested-only".equals(e.requestedModel())));
    }
    @Test void invalidPhasesAndRawUnwrappedBodiesCannotReachTheLedger() throws Exception {
        for(String data:new String[]{"{\"phase\":\"OTHER\"}","{\"phase\":\"STARTED\",\"usageEvidence\":{}}","{\"phase\":\"TERMINAL\",\"usageEvidence\":[]}"})
            call(data).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/agent/chat/dispatches/dispatch/usage").principal(principal)
                .contentType(MediaType.APPLICATION_JSON).content("{\"phase\":\"STARTED\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recorder,authentication);
    }
}
