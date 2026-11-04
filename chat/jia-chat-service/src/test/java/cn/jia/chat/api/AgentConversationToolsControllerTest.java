package cn.jia.chat.api;

import cn.jia.agent.api.AgentConversationToolsController;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.agent.skill.SkillPackagePublicationService;
import cn.jia.agent.api.SkillConversationToolAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentConversationToolsControllerTest {
    private final SkillConversationToolAdapter service=mock(SkillConversationToolAdapter.class);
    private final AgentConversationToolsController controller=new AgentConversationToolsController(service);
    private final Authentication principal=mock(Authentication.class);
    private final MockHttpServletRequest request=new MockHttpServletRequest();
    @Test void mutationReceiptUsesPublicEnvelopeAndDecimalStrings() {
        var command=new SkillPackagePublicationService.RetireProduct("p",4);
        when(service.retire(principal,"r","t",command)).thenReturn(new SkillPackagePublicationService.RetirementReceipt("p","RETIRED",5,"RETIRED"));
        var response=controller.call(principal,"r","t","skill_retire","{\"productId\":\"p\",\"expectedProductVersion\":\"4\"}",request);
        var wire=JsonMapper.builder().build().valueToTree(response.getBody());
        assertEquals("5",wire.at("/data/productVersion").asText());
        assertTrue(wire.at("/data/productVersion").isTextual());
        assertEquals("RETIRED",wire.at("/data/disposition").asText());
        assertEquals("private, no-store",response.getHeaders().getFirst("Cache-Control"));
    }
    @Test void duplicateKeysTrailingJsonAndQueryAreNotAccepted() {
        for(String body:new String[]{"{\"productId\":\"p\",\"productId\":\"other\"}","{\"productId\":\"p\"} {}"}) {
            var failure=assertThrows(Exception.class,()->controller.call(principal,"r","t","skill_read",body,request));
            assertEquals(400,controller.failure(failure).getStatusCode().value());
        }
        request.setQueryString("owner=other");
        assertThrows(IllegalArgumentException.class,()->controller.catalog(principal,"r","t",request));
        verifyNoInteractions(service);
    }
    @Test void failuresAreNotSuccessfulOrLeakedAsExceptionMessages() {
        assertEquals(409,controller.failure(new SkillMarketplaceException(409,"SKILL_PRODUCT_CHANGED")).getStatusCode().value());
        var response=controller.failure(new IllegalStateException("private credential"));
        assertEquals(503,response.getStatusCode().value());
        assertFalse(JsonMapper.builder().build().writeValueAsString(response.getBody()).contains("private credential"));
    }
    @Test void actualHttpEnvelopeUnwrapsDataAndPreservesResponseCorrelation() throws Exception {
        var converters=new java.util.ArrayList<org.springframework.http.converter.HttpMessageConverter<?>>();
        converters.add(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8));
        converters.add(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter());
        new cn.jia.core.config.ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(),new cn.jia.core.config.ApiRequestEnvelopeAdvice())
                .setMessageConverters(converters.toArray(org.springframework.http.converter.HttpMessageConverter[]::new))
                .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
        when(service.read(principal,"r","t","p")).thenReturn(new SkillPackagePublicationService.ProductView("p","标题","描述","PUBLISHED","2","v"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/agent/chat/requests/r/turns/t/tools/skill_read")
                .principal(principal).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Request-Id","tool-wire")
                .content("{\"protocolVersion\":1,\"requestId\":\"tool-wire\",\"data\":{\"productId\":\"p\"}}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.requestId").value("tool-wire"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.productVersion").value("2"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/agent/chat/requests/r/turns/t/tools/skill_read")
                .principal(principal).contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("X-Request-Id","tool-wire")
                .content("{\"productId\":\"p\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        verify(service,times(1)).read(principal,"r","t","p");
    }

}
