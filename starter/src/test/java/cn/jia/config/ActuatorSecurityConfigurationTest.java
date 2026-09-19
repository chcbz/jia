package cn.jia.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActuatorSecurityConfigurationTest {
    @Test
    void selectsOnlyTheActuatorEndpointTree() {
        assertTrue(ActuatorSecurityConfiguration.selectsActuator(request("/actuator")));
        assertTrue(ActuatorSecurityConfiguration.selectsActuator(request("/actuator/health")));
        assertFalse(ActuatorSecurityConfiguration.selectsActuator(request("/agent/tasks")));
        assertFalse(ActuatorSecurityConfiguration.selectsActuator(request("/actuatorx/health")));
    }

    private static MockHttpServletRequest request(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }
}
