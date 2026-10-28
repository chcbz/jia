package cn.jia.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeControlledImageV3SecurityIntegrationTest {
    @Test void retiredV3InboxInputsAndProviderStartRoutesNeverEnterRuntimeLane() {
        for(String path:List.of(
                "/internal/agent/tasks/conversation-executions/controlled-image-v3-commands",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3",
                "/internal/agent/tasks/task-1/runs/run-1/conversation/provider-start-controlled-image-v3"))
            assertFalse(AgentRuntimeAuthenticationFilter.allowed(request(
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




    private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder spoolRequest(
            String path,String proof,byte[] bytes) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(path)
            .file(new org.springframework.mock.web.MockMultipartFile("file","bird.png","image/png",bytes))
            .param("proof",proof).header("Authorization","AgentRuntime rts1_"+"a".repeat(64))
            .header("X-Agent-Id","agent").header("X-Agent-Runtime-Id","runtime")
            .header("X-Agent-Installation-Id","rti_"+"a".repeat(32)).header("X-Agent-Host-Id","host")
            .header("X-Agent-Session-Generation","1");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder nativeInputsRequest() {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
          "/internal/agent/tasks/task-1/runs/run-1/conversation/inputs-v3")
          .header("Authorization","AgentRuntime rts1_"+"a".repeat(64)).header("X-Agent-Id","agent")
          .header("X-Agent-Runtime-Id","runtime")
            .header("X-Agent-Installation-Id","rti_"+"a".repeat(32)).header("X-Agent-Host-Id","host")
            .header("X-Agent-Session-Generation","1").contentType("application/json")
          .content("{\"version\":1,\"token\":\"11111111-1111-1111-1111-111111111111\"}");
    }

    private static MockHttpServletRequest request(String method,String path) {
        return new MockHttpServletRequest(method,path);
    }
}
