package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputReadSecurityConfigurationTest {
    @Test
    void exactDeliveryListRouteUsesUserJwtChain() {
        assertTrue(matches("GET", "/agent/tasks/task-1/deliveries"));
        assertFalse(matches("POST", "/agent/tasks/task-1/deliveries"));
        assertFalse(matches("GET", "/agent/tasks/task-1/deliveries/extra"));
    }

    private boolean matches(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return OutputReadSecurityConfiguration.matches(request);
    }
}
