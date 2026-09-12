package cn.jia.agent.api;

import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputLeaseHttpResult;
import cn.jia.agent.output.OutputLeaseService;
import cn.jia.agent.output.OutputTicketAuthorization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OutputLeaseControllerTest {
    private static final String RUN = "22222222222222222222222222222222";
    private static final String BEARER = "Bearer " + "a".repeat(43);
    private OutputLeaseService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OutputLeaseService.class);
        mvc = MockMvcBuilders.standaloneSetup(new OutputLeaseController(service)).build();
    }

    @Test
    void strictBodyRejectsDuplicateUnknownNumericAndTrailingContent() throws Exception {
        String[] invalid = {
                "{\"runId\":\"" + RUN + "\",\"runId\":\"" + RUN
                        + "\",\"expectedVersion\":\"0\",\"leaseDurationMillis\":\"120000\"}",
                "{\"runId\":\"" + RUN + "\",\"expectedVersion\":\"0\",\"leaseDurationMillis\":\"120000\",\"agentId\":\"agt_00000000000000000000000000000000\"}",
                "{\"runId\":\"" + RUN + "\",\"expectedVersion\":0,\"leaseDurationMillis\":\"120000\"}",
                "{\"runId\":\"" + RUN + "\",\"expectedVersion\":\"0\",\"leaseDurationMillis\":\"120000\"}{}"
        };
        for (int index = 0; index < invalid.length; index++) {
            mvc.perform(post("/agent/tasks/task-1/work-items/work-1/lease/claim")
                            .principal(principal()).header(HttpHeaders.AUTHORIZATION, BEARER)
                            .header("Idempotency-Key", "lease-invalid-key-" + index)
                            .contentType("application/json").content(invalid[index]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"));
        }
        verify(service, never()).mutate(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void actionSpecificShapesAndExactReceiptBodyArePreserved() throws Exception {
        when(service.mutate(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(new OutputLeaseHttpResult(
                200, "{\"code\":\"E0\",\"data\":{\"workItemId\":\"work-1\",\"status\":\"claimed\",\"version\":\"1\",\"leaseToken\":\"lease-x\",\"leaseUntil\":\"123\"}}"));

        mvc.perform(post("/agent/tasks/task-1/work-items/work-1/lease/claim")
                        .principal(principal()).header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "lease-example-claim-0001")
                        .header("X-Request-ID", "lease-request-1")
                        .contentType("application/json")
                        .content("{\"runId\":\"" + RUN
                                + "\",\"expectedVersion\":\"0\",\"leaseDurationMillis\":\"120000\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "lease-request-1"))
                .andExpect(jsonPath("$.data.version").value("1"));

        for (String pathAndBody : List.of(
                "claim|{\"runId\":\"" + RUN + "\",\"expectedVersion\":\"0\"}",
                "start|{\"runId\":\"" + RUN + "\",\"expectedVersion\":\"1\"}",
                "heartbeat|{\"runId\":\"" + RUN + "\",\"expectedVersion\":\"2\",\"leaseToken\":\"lease-x\"}",
                "release|{\"runId\":\"" + RUN + "\",\"expectedVersion\":\"3\"}")) {
            String[] values = pathAndBody.split("\\|", 2);
            mvc.perform(post("/agent/tasks/task-1/work-items/work-1/lease/" + values[0])
                            .principal(principal()).header(HttpHeaders.AUTHORIZATION, BEARER)
                            .header("Idempotency-Key", "lease-semantic-key-" + values[0])
                            .contentType("application/json").content(values[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void recoveryRequiresRunTicketPrincipalAndReturnsExactServiceBody() throws Exception {
        when(service.recover(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OutputLeaseHttpResult(200,
                        "{\"code\":\"E0\",\"data\":{\"workItemId\":\"work-1\",\"status\":\"ready\",\"version\":\"0\"}}"));
        mvc.perform(get("/agent/tasks/task-1/work-items/work-1/lease")
                        .principal(principal()).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ready"));
        mvc.perform(get("/agent/tasks/task-1/work-items/work-1/lease")
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isUnauthorized());
    }

    private UsernamePasswordAuthenticationToken principal() {
        OutputTicketAuthorization ticket = new OutputTicketAuthorization(
                "owner", "client", RUN, OutputConstants.SOURCE_TASK, "task-1",
                "agt_11111111111111111111111111111111", "7", "runtime-1", true,
                OutputConstants.R2_LEASE_TICKET_OPERATIONS, System.currentTimeMillis() + 60_000,
                OutputConstants.RUN_ACTIVE, 1, "work-1");
        return new UsernamePasswordAuthenticationToken(ticket, null, List.of());
    }
}
