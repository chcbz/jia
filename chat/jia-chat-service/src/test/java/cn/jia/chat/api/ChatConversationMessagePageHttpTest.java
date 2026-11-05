package cn.jia.chat.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.ChatBountyAssetProjector;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatConversationMessagePageHttpTest {
    private ChatConversationService service;
    private ChatBountyAssetProjector assets;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(ChatConversationService.class); assets = mock(ChatBountyAssetProjector.class);
        var controller = new ChatController(null, service, null, null, null, null, null, null, null, null, null);
        controller.setBountyAssets(assets);
        var converters = new ArrayList<HttpMessageConverter<?>>();
        converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice(), new ExceptionHandlerAdvice())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new))
                .addFilters(new RequestIdFilter()).build();
    }
    @Test void completeWireMatchesSharedPageFixtureAndProjectsOnlyPageAssets() throws Exception {
        var message = new ChatMessageEntity().setId(9007199254740993L).setConversationId("100")
                .setMessageType("USER").setContent("fixture message").setMetadata("{}").setJiacn("owner")
                .setSyncStatus("PENDING").setConversationType("juyiting").setSenderType("user").setSenderName("fixture user");
        message.setCreateTime(1000L); message.setUpdateTime(1000L); message.setTenantId("0"); message.setClientId("client");
        when(service.findMessagePage("100", null)).thenReturn(new ChatConversationService.MessagePage(List.of(message), true, "1000:9007199254740993"));
        when(assets.partsFor(any(), eq("100"), eq(List.of(9007199254740993L)))).thenReturn(Map.of());
        var response = mvc.perform(get("/chat/conversation/messages").param("id", "100")
                .header("X-Request-Id", "hall-message-page-fixture")).andExpect(status().isOk()).andReturn().getResponse();
        var mapper = new ObjectMapper();
        var fixture = mapper.readTree(getClass().getResourceAsStream("/contracts/hall-message-page.json"));
        var body = mapper.readTree(response.getContentAsString());
        assertEquals(fixture.get("data"), body.get("data"));
        assertEquals(fixture.get("protocolVersion"), body.get("protocolVersion"));
        assertEquals(fixture.get("requestId"), body.get("requestId"));
        assertEquals(fixture.get("code"), body.get("code"));
        assertEquals("hall-message-page-fixture", response.getHeader("X-Request-Id"));
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        verify(assets).partsFor(new PersonalWorkspaceExecutionService.OwnerScope("0", "client", "owner"), "100", List.of(9007199254740993L));
        verify(service).findMessagePage("100", null); verifyNoMoreInteractions(service, assets);
    }
    @Test void emptyPageDoesNotReadAttachmentsAndInvalidCursorIs400() throws Exception {
        when(service.findMessagePage("100", null)).thenReturn(new ChatConversationService.MessagePage(List.of(), false, null));
        var response = mvc.perform(get("/chat/conversation/messages").param("id", "100")).andExpect(status().isOk()).andReturn().getResponse();
        var data = new ObjectMapper().readTree(response.getContentAsString()).get("data");
        assertTrue(data.get("items").isEmpty()); assertTrue(data.get("nextCursor").isNull());
        verifyNoInteractions(assets);
        when(service.findMessagePage("100", "bad")).thenThrow(new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST, "Invalid message cursor"));
        mvc.perform(get("/chat/conversation/messages").param("id", "100").param("cursor", "bad")).andExpect(status().isBadRequest());
    }
}
