package cn.jia.agent.api;

import cn.jia.agent.entity.AgentPersonaBindRequestDTO;
import cn.jia.core.security.AllowSensitiveOutput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentControllerTest {

    @Test
    void bindPersonaAllowsSensitiveOutputForSetupApiKey() throws Exception {
        Method method = AgentController.class.getDeclaredMethod(
                "bindPersona", String.class, AgentPersonaBindRequestDTO.class);

        assertNotNull(method.getAnnotation(AllowSensitiveOutput.class));
    }
}
