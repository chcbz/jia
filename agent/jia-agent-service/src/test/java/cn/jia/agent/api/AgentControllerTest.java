package cn.jia.agent.api;

import cn.jia.agent.entity.AgentPersonaBindRequestDTO;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.HostingRentAdmissionException;
import cn.jia.core.security.AllowSensitiveOutput;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerTest {

    @Test
    void bindPersonaAllowsSensitiveOutputForSetupApiKey() throws Exception {
        Method method = AgentController.class.getDeclaredMethod(
                "bindPersona", String.class, AgentPersonaBindRequestDTO.class);

        assertNotNull(method.getAnnotation(AllowSensitiveOutput.class));
    }

    @Test
    void absentEmptyAndBlankModeKeepLegacyLocalBindingCompatibility() throws Exception {
        AgentService service = mock(AgentService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(post("/agent/personas/wuyong/bind"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/personas/wuyong/bind")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/personas/wuyong/bind")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"   \"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/agent/personas/wuyong/bind")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"local\"}"))
                .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(3)).bindPersona("wuyong");
        verify(service).bindPersona("wuyong", "local");
    }

    @Test
    void legacyServerBindSurfacesStableUnavailableHttpStatus() throws Exception {
        AgentService service = mock(AgentService.class);
        when(service.bindPersona("wuyong", "server")).thenThrow(
                new HostingRentAdmissionException(
                        HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_CONFIGURED));

        mvc(service).perform(post("/agent/personas/wuyong/bind")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"server\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("HOSTING_RENT_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.status").value(503));
    }

    private static MockMvc mvc(AgentService service) {
        return MockMvcBuilders.standaloneSetup(
                new AgentController(service, mock(AbilityEvaluationService.class))).build();
    }
}
