package cn.jia.chat.api;

import cn.jia.chat.service.*;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatBountyRequestIndexHttpTest {
    private ChatBountyRequestIndexService service;
    private MockMvc mvc;
    private JwtAuthenticationToken auth;
    @BeforeEach void setup() {
        service = mock(ChatBountyRequestIndexService.class);
        var identities = mock(HumanSenderIdentityResolver.class);
        var tenants = mock(TenantScopeResolver.class);
        when(tenants.resolve(any())).thenReturn("0");
        when(identities.resolve(any())).thenReturn(new ServerResolvedSender("user", "Owner", "owner", "client", null));
        var context = new EsContext(); context.setTenantId("0"); context.setJiacn("owner"); context.setClientId("client");
        EsContextHolder.setContext(context);
        auth = new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg", "none")
                .claim("jiacn", "owner").claim("client_id", "client").build(), List.of());
        var converters = new ArrayList<HttpMessageConverter<?>>();
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc = MockMvcBuilders.standaloneSetup(new ChatBountyRequestIndexController(service, identities, tenants))
                .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice(), new ExceptionHandlerAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).build();
    }
    @AfterEach void cleanup() { EsContextHolder.clearContext(); }
    @Test void eventTargetedGetMatchesSharedEnvelopeAndDoesNotChangePageWire() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/hall-request-index-entry.json"));
        var page = mapper.treeToValue(fixture.get("data"), ChatBountyRequestIndexService.Page.class);
        var query = new ChatBountyRequestIndexService.Query("1", null, null, null, "r7");
        when(service.read("0", "owner", "client", "101", query)).thenReturn(page);
        var response = mvc.perform(get("/chat/conversations/101/requests").param("expectedGeneration", "1").param("requestId", "r7")
                .principal(auth).header("X-Request-Id", "hall-request-index-entry"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var body = mapper.readTree(response.getContentAsString());
        for (String field : List.of("data", "code", "protocolVersion", "requestId")) assertEquals(fixture.get(field), body.get(field));
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        verify(service).read("0", "owner", "client", "101", query); verifyNoMoreInteractions(service);
    }
    @Test void duplicateRequestIdIsRejectedAtProtocolBoundary() throws Exception {
        mvc.perform(get("/chat/conversations/101/requests").param("requestId", "one", "two").principal(auth))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
