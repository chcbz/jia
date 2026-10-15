package cn.jia.chat.api;

import cn.jia.chat.memory.MemoryRepository;
import cn.jia.chat.service.*;
import cn.jia.chat.service.impl.AgentTaskThreadMemoryGuard;
import cn.jia.core.config.*;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.core.redis.RedisService;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public decoder stays strict; the internal cancellation CAS continues to use Long. */
class ChatCancelWireHttpTest {
    private ChatDeliberationService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(ChatDeliberationService.class);
        var controller = new ChatController(mock(ChatClient.class), mock(ChatConversationService.class),
                mock(RedisService.class), mock(ChatClient.Builder.class), mock(ChatConversationEventBroker.class),
                mock(BuiltinHallAgentSupport.class), mock(JuyitingConversationScopeService.class),
                mock(JuyitingAgentRelayService.class), mock(MemoryRepository.class),
                mock(AgentTaskThreadMemoryGuard.class), mock(HumanSenderIdentityResolver.class));
        controller.setChatDeliberationService(service);
        var converters = new ArrayList<HttpMessageConverter<?>>();
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice(), new ExceptionHandlerAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).build();
        var scope = new EsContext(); scope.setTenantId("0"); scope.setClientId("client"); scope.setJiacn("owner");
        EsContextHolder.setContext(scope);
    }
    @AfterEach void clear() { EsContextHolder.clearContext(); }
    private org.springframework.test.web.servlet.ResultActions call(String data) throws Exception {
        var auth = new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg", "none")
                .claim("sub", "owner").claim("jiacn", "owner").claim("client_id", "client").claim("tenant_id", "0").build());
        return mvc.perform(post("/chat/turns/turn/cancel").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "cancel-wire-test")
                .content("{\"protocolVersion\":1,\"requestId\":\"cancel-wire-test\",\"data\":" + data + "}"));
    }
    @Test void canonicalDecimalIsConvertedWithoutPrecisionLoss() throws Exception {
        for (String value : List.of("0", "3", "9007199254740993", "9223372036854775807")) {
            call("{\"expectedStateVersion\":\"" + value + "\"}").andExpect(status().isOk());
            verify(service).cancelTurn("0", "owner", "client", "turn", Long.valueOf(value), null);
        }
    }
    @Test void malformedOrNumericVersionNeverReachesMutation() throws Exception {
        for (String value : List.of("3", "3.0", "true", "\"03\"", "\"-1\"", "\"+3\"", "\" 3\"", "\"\"", "\"9223372036854775808\""))
            call("{\"expectedStateVersion\":" + value + "}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void staleVersionStillReturnsConflict() throws Exception {
        when(service.cancelTurn("0", "owner", "client", "turn", 3L, null))
                .thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, "Turn state version changed"));
        call("{\"expectedStateVersion\":\"3\"}").andExpect(status().isConflict());
    }
    @Test void optionalVersionRemainsOptional() throws Exception {
        assertNull(ChatController.parseExpectedStateVersion(null));
        call("{}").andExpect(status().isOk());
        verify(service).cancelTurn("0", "owner", "client", "turn", null, null);
    }
}
