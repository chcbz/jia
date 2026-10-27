package cn.jia.chat.api;

import cn.jia.chat.service.*;
import cn.jia.core.config.*;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.ArrayList;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatBountyTopicControllerTest {
    final ChatBountyTopicService topics=mock(ChatBountyTopicService.class);
    final HumanSenderIdentityResolver identities=mock(HumanSenderIdentityResolver.class);
    final TenantScopeResolver tenants=mock(TenantScopeResolver.class);
    final Authentication auth=mock(Authentication.class);
    final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner","client",DisplayNameSource.JIACN);
    final ChatBountyTopicService.Receipt receipt=new ChatBountyTopicService.Receipt("101","1","102","1","537","wuyong");
    MockMvc mvc;
    @BeforeEach void setup() {
        List<HttpMessageConverter<?>> converters=new ArrayList<>();
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc=MockMvcBuilders.standaloneSetup(new ChatBountyTopicController(topics,identities,tenants))
                .setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice(),new ExceptionHandlerAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).build();
        when(tenants.resolve(auth)).thenReturn("0");when(identities.resolve(any())).thenReturn(sender);
        when(topics.create("0",sender,"101","topic-key-001",1)).thenReturn(receipt);
        when(topics.get("0",sender,"101","topic-key-001")).thenReturn(receipt);
    }
    @AfterEach void clear(){EsContextHolder.clearContext();}
    private String body(String data){return "{\"protocolVersion\":1,\"requestId\":\"topic-http\",\"data\":"+data+"}";}
    @Test void createsThroughCommonEnvelopeUsingServerIdentityAndReturnsCanonicalReceipt() throws Exception {
        mvc.perform(post("/chat/conversations/101/topics").principal(auth).header("Idempotency-Key","topic-key-001")
                .contentType(MediaType.APPLICATION_JSON).content(body("{\"expectedConversationGeneration\":\"1\",\"futureDisplayHint\":true}")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.protocolVersion").value(1)).andExpect(jsonPath("$.requestId").value("topic-http"))
                .andExpect(jsonPath("$.code").value("E0")).andExpect(jsonPath("$.data.conversationId").value("102"))
                .andExpect(jsonPath("$.data.taskId").value("537"));
        verify(topics).create("0",sender,"101","topic-key-001",1);
    }
    @Test void readbackOnlyReadsOriginalOperation() throws Exception {
        mvc.perform(get("/chat/conversations/101/topics/topic-key-001").principal(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceConversationId").value("101"));
        verify(topics).get("0",sender,"101","topic-key-001");verify(topics,never()).create(any(),any(),any(),any(),anyLong());
    }
    @Test void malformedGenerationOrProtocolNeverCallsCreation() throws Exception {
        for(String data:List.of("{}","{\"expectedConversationGeneration\":1}","{\"expectedConversationGeneration\":\"0\"}")) {
            mvc.perform(post("/chat/conversations/101/topics").principal(auth).header("Idempotency-Key","topic-key-001")
                    .contentType(MediaType.APPLICATION_JSON).content(body(data))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/chat/conversations/101/topics").principal(auth).header("Idempotency-Key","topic-key-001")
                .header("X-Request-Id","different").contentType(MediaType.APPLICATION_JSON)
                .content(body("{\"expectedConversationGeneration\":\"1\"}"))).andExpect(status().isBadRequest());
        verifyNoInteractions(topics);
    }
    @Test void absentReceiptIs404NotSuccessOrAutomaticCreation() throws Exception {
        when(topics.get("0",sender,"101","topic-key-001")).thenThrow(new ChatDeliberationException(
                ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Topic is unavailable"));
        mvc.perform(get("/chat/conversations/101/topics/topic-key-001").principal(auth)).andExpect(status().isNotFound());
        verify(topics,never()).create(any(),any(),any(),any(),anyLong());
    }
}
