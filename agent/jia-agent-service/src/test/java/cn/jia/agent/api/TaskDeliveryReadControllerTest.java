package cn.jia.agent.api;

import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.TaskDeliveryQueryService;
import cn.jia.agent.output.dto.TaskDeliveryPageDTO;
import cn.jia.agent.output.dto.TaskDeliveryViewDTO;
import cn.jia.agent.output.dto.TaskDeliveryViewItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskDeliveryReadControllerTest {
    private TaskDeliveryQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TaskDeliveryQueryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TaskDeliveryReadController(service)).build();
    }

    @Test
    void ownerListsFrozenDeliveryItemsWithOpaquePageInputs() throws Exception {
        when(service.list("owner", "client", "owner", "task-1", "opaque", 7))
                .thenReturn(new TaskDeliveryPageDTO(List.of(new TaskDeliveryViewDTO(
                        "delivery-1", "task-1", "1", "0", "8", "SUBMITTED",
                        "ready", List.of(new TaskDeliveryViewItemDTO(
                                "artifact-1", "2", "archive")),
                        List.of("accept", "request_changes"))), null, "1000"));

        mvc.perform(get("/agent/tasks/task-1/deliveries?cursor=opaque&limit=7")
                        .principal(principal()).header("X-Request-ID", "read-request-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "read-request-1"))
                .andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.items[0].deliveryId").value("delivery-1"))
                .andExpect(jsonPath("$.data.items[0].items[0].artifactId")
                        .value("artifact-1"));
        verify(service).list("owner", "client", "owner", "task-1", "opaque", 7);
    }

    @Test
    void duplicateCursorAndHiddenTaskFailClosed() throws Exception {
        mvc.perform(get("/agent/tasks/task-1/deliveries?cursor=a&cursor=b")
                        .principal(principal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"));
        when(service.list("owner", "client", "owner", "hidden", null, null))
                .thenThrow(new OutputDeliveryException(
                        "OUTPUT_NOT_FOUND", "Formal delivery source is unavailable",
                        404, false));
        mvc.perform(get("/agent/tasks/hidden/deliveries").principal(principal()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OUTPUT_NOT_FOUND"));
    }

    private JwtAuthenticationToken principal() {
        Instant now = Instant.now();
        Jwt jwt = new Jwt("token", now, now.plusSeconds(60),
                java.util.Map.of("alg", "none"),
                java.util.Map.of("sub", "owner", "jiacn", "owner",
                        "client_id", "client"));
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("output-read")));
    }
}
