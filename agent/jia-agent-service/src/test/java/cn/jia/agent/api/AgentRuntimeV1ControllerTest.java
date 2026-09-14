package cn.jia.agent.api;

import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentRuntimeV1EnrollmentResult;
import cn.jia.agent.entity.AgentRuntimeV1InstallationView;
import cn.jia.agent.service.AgentRuntimeV1Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRuntimeV1ControllerTest {
    private AgentRuntimeV1Service runtime;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        runtime = mock(AgentRuntimeV1Service.class);
        mvc = MockMvcBuilders.standaloneSetup(new AgentRuntimeV1Controller(runtime)).build();
    }

    @Test void installerRuntimeAndAckUseExactJsonResultDataEnvelope() throws Exception {
        AgentRuntimeV1InstallationView view = view("ACTIVE");
        when(runtime.enroll(any(), anyLong())).thenReturn(new AgentRuntimeV1EnrollmentResult(view, "rta1_secret"));
        when(runtime.session(eq("rta1_secret"), any(), anyLong())).thenReturn(view);
        when(runtime.heartbeat(eq("rta1_secret"), any(), anyLong())).thenReturn(view);
        when(runtime.acknowledge(eq("rta1_secret"), eq("msg-1"), any(), anyLong())).thenReturn(
                new AgentCommandAckResult(AgentCommandAckResult.Kind.ADVANCED, "RECEIVED", 1));

        mvc.perform(post("/agent/runtime/v1/enroll").contentType("application/json").content(enrollmentJson()))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":{")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"runtimeAuthorization\":\"rta1_secret\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("enrollmentSecret"))));
        mvc.perform(post("/agent/runtime/v1/session").header(HttpHeaders.AUTHORIZATION, "Bearer rta1_secret")
                        .contentType("application/json").content(runtimeJson()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":{")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"ACTIVE\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("runtimeAuthorization"))));
        mvc.perform(post("/agent/runtime/v1/heartbeat").header(HttpHeaders.AUTHORIZATION, "Bearer rta1_secret")
                        .contentType("application/json").content(runtimeJson()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":{")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"ACTIVE\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("rta1_secret"))));
        mvc.perform(post("/agent/runtime/v1/commands/msg-1/acks").header(HttpHeaders.AUTHORIZATION, "Bearer rta1_secret")
                        .contentType("application/json").content(ackJson()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":{")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"kind\":\"ADVANCED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"RECEIVED\"")));
        verify(runtime).acknowledge(eq("rta1_secret"), eq("msg-1"), any(), anyLong());
    }

    @Test void statusIsRedactedAndLegacyUrlCredentialsAreRejected() throws Exception {
        when(runtime.status("tenant-a", "client-a", "rti-1")).thenReturn(view("ACTIVE"));
        mvc.perform(get("/agent/runtime/v1/installations/rti-1").principal(jwt()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"data\":{")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"ACTIVE\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("runtimeAuthorization"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("enrollmentSecret"))));
        mvc.perform(post("/agent/runtime/v1/session?api_key=legacy").header(HttpHeaders.AUTHORIZATION, "Bearer rta1_secret")
                        .contentType("application/json").content(runtimeJson()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("legacy"))));
        mvc.perform(post("/agent/runtime/v1/session?apiKey=legacy").header(HttpHeaders.AUTHORIZATION, "Bearer rta1_secret")
                        .contentType("application/json").content(runtimeJson()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("legacy"))));
    }

    private static AgentRuntimeV1InstallationView view(String state) {
        return new AgentRuntimeV1InstallationView("rti-1", "tenant-a", "client-a",
                "agt_0123456789abcdef0123456789abcdef", "1", "b".repeat(64), 99_999L, state, null);
    }
    private static JwtAuthenticationToken jwt() {
        Jwt jwt = new Jwt("token", Instant.ofEpochSecond(1), Instant.ofEpochSecond(9_999_999_999L),
                Map.of("alg", "none"), Map.of("jiacn", "tenant-a", "client_id", "client-a"));
        return new JwtAuthenticationToken(jwt, java.util.List.of(new SimpleGrantedAuthority("user")));
    }
    private static String enrollmentJson() { return """
            {"installationId":"rti-1","tenantId":"tenant-a","clientId":"client-a",
             "canonicalAgentId":"agt_0123456789abcdef0123456789abcdef","manifestVersion":"1",
             "manifestSha256":"%s","enrollmentSecret":"installer-secret"}
            """.formatted("b".repeat(64)); }
    private static String runtimeJson() { return """
            {"installationId":"rti-1","tenantId":"tenant-a","clientId":"client-a",
             "canonicalAgentId":"agt_0123456789abcdef0123456789abcdef","manifestVersion":"1",
             "manifestSha256":"%s","health":"ok"}
            """.formatted("b".repeat(64)); }
    private static String ackJson() { return """
            {"messageId":"msg-1","correlationId":"corr-1","commandId":"cmd-1","taskId":"task-1",
             "tenantId":"tenant-a","clientId":"client-a",
             "canonicalAgentId":"agt_0123456789abcdef0123456789abcdef","status":"RECEIVED"}
            """; }
}
