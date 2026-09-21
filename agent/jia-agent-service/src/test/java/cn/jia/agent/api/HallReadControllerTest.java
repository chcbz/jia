package cn.jia.agent.api;

import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallRequestDraftService.OwnerScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HallReadControllerTest {
    private HallReadService service;
    private MockMvc mvc;
    @BeforeEach void setUp() {
        service = mock(HallReadService.class);
        mvc = MockMvcBuilders.standaloneSetup(new HallReadController(service))
                .addFilters(new HallRequestDraftCacheControlFilter()).build();
    }
    @Test void overviewContractHasExplicitSourceFailuresAndNullCount() throws Exception {
        var source = new HallReadService.SourceStatus("error", "HALL_SOURCE_UNAVAILABLE");
        var section = new HallReadService.Section("error", Map.of("private", new HallReadService.Partition(
                List.of(), "error", null, null, "HALL_SOURCE_UNAVAILABLE")));
        when(service.overview(new OwnerScope("0", "client-a", "owner-a")))
                .thenReturn(new HallReadService.Overview(1, Map.of("recent", section, "needsAction", section),
                        Map.of("recent", Map.of("private", source), "needsAction", Map.of("private", source)), 1000));
        mvc.perform(get("/agent/hall/overview").principal(jwt("owner-a", "client-a")))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.sections.recent.partitions.private.status").value("error"))
                .andExpect(jsonPath("$.sourceStatus.recent.private.errorCode").value("HALL_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.sections.recent.partitions.private.count").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test void authScopeAndUnknownOrDuplicateQueryParametersAreRejected() throws Exception {
        mvc.perform(get("/agent/hall/overview")).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        mvc.perform(get("/agent/hall/overview").principal(jwt("0", "client-a"))).andExpect(status().isForbidden());
        mvc.perform(get("/agent/hall/overview?q=filter").principal(jwt("owner-a", "client-a"))).andExpect(status().isBadRequest());
        mvc.perform(get("/agent/hall/items?ownerJiacn=other").principal(jwt("owner-a", "client-a"))).andExpect(status().isBadRequest());
        mvc.perform(get("/agent/hall/items?kind=private&kind=task").principal(jwt("owner-a", "client-a"))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void itemQueryIsForwardedExactlyAndArchiveIsExplicit422() throws Exception {
        var scope = new OwnerScope("0", "client-a", "owner-a");
        when(service.items(scope, "private", "archive", null, null))
                .thenThrow(new HallReadService.Failure(HallReadService.Reason.VIEW_UNAVAILABLE));
        mvc.perform(get("/agent/hall/items?kind=private&view=archive").principal(jwt("owner-a", "client-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("HALL_READ_VIEW_UNAVAILABLE"));
        verify(service).items(scope, "private", "archive", null, null);
    }
    private static JwtAuthenticationToken jwt(String owner, String client) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none")
                .subject(owner).claim("jiacn", owner).claim("client_id", client)
                .issuedAt(Instant.ofEpochSecond(1)).expiresAt(Instant.ofEpochSecond(2)).build());
    }
}
