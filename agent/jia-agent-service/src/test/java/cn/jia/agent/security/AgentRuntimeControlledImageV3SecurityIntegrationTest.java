package cn.jia.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeControlledImageV3SecurityIntegrationTest {
    @Test void onlyExactV3InboxInputsAndProviderStartRoutesEnterRuntimeLane() {
        for(String path:List.of(
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/provider-start-controlled-image-v3"))
            assertTrue(AgentRuntimeAuthenticationFilter.allowed(request(
                    path.endsWith("commands")?"GET":"POST",path)),path);

        for(String path:List.of(
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands/",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v4",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/provider-start-controlled-image-v3/extra",
                "/internal/agent/tasks/task%2Fother/runs/run-1/conversation/inputs-v3",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/../provider-start-controlled-image-v3"))
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("POST",path)),path);
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("GET",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3")));
        assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("POST",
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands")));
    }

    @Test void stagedResultRecoveryIsOneExactNativePostNotAnUploadOrStartCapability() {
        String path="/internal/agent/tasks/task-1/runs/run-1/conversation/result-commits/pwe_m_"+"a".repeat(64);
        assertTrue(AgentRuntimeAuthenticationFilter.allowed(request("POST",path)));
        for(String method:List.of("GET","PUT","DELETE"))
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(request(method,path)));
        for(String suffix:List.of("/content","/start","/lease","/extra","/"))
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(request("POST",path+suffix)));
    }

    private static MockHttpServletRequest request(String method,String path) {
        return new MockHttpServletRequest(method,path);
    }
}
