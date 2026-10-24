package cn.jia.agent.api;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.service.AgentTaskContextPackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Focused tenant binding contract; the historical ContextPackControllerTest is not compilable. */
class AgentTaskContextPackTenantScopeTest {
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private AgentTaskEventsGate gate;
    private AgentTaskContextPackService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gate = mock(AgentTaskEventsGate.class);
        service = mock(AgentTaskContextPackService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new AgentTaskContextPackController(service, gate,
                        mock(cn.jia.agent.security.AgentRuntimeAuthenticationService.class))).build();
    }

    @Test
    void regionOneNoncanonicalZeroOtherDomainAndMissingClaimsCannotReadLegacyPack() throws Exception {
        for (JwtAuthenticationToken principal : List.of(
                jwtForArea("1", "cyf"), jwtForArea("00", "cyf"),
                jwtForArea("0", "other"), jwtForArea(null, "cyf"),
                jwtForArea("0", null))) {
            mvc.perform(get("/agent/tasks/{taskId}/context-pack", "task-1")
                            .principal(principal))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        verifyNoInteractions(gate, service);
    }

    @Test
    void exactRegionZeroReachesOnlyLegacyGateBeforeGenerator() throws Exception {
        mvc.perform(get("/agent/tasks/{taskId}/context-pack", "task-1")
                        .principal(jwtForArea("0", "cyf")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        verify(gate).allows("0", "client-a");
        verifyNoInteractions(service);
    }

    private static JwtAuthenticationToken jwtForArea(String area, String domain) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a").claim("sub", ACTOR)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (area != null) builder.claim("tenant_id", area);
        if (domain != null) builder.claim("data_domain_id", domain);
        return new JwtAuthenticationToken(builder.build(), List.of(), ACTOR);
    }
}
