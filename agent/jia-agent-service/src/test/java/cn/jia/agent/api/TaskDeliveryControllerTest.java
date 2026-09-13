package cn.jia.agent.api;

import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.TaskDeliveryHttpResult;
import cn.jia.agent.output.TaskDeliverySubmissionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskDeliveryControllerTest {
    private static final String RUN = "22222222222222222222222222222222";
    private static final String BEARER = "Bearer " + "a".repeat(43);
    private TaskDeliverySubmissionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TaskDeliverySubmissionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TaskDeliveryController(service)).build();
    }

    @Test
    void strictBodyRejectsDuplicatesUnknownFieldsNumericVersionsAndTrailingTokens()
            throws Exception {
        String validItem = "{\"artifactId\":\"artifact-1\",\"version\":\"1\"}";
        String prefix = "{\"runId\":\"" + RUN
                + "\",\"workItemId\":\"work-1\",\"expectedTaskVersion\":\"1\","
                + "\"expectedWorkItemVersion\":\"2\",\"leaseToken\":\"lease-token\","
                + "\"summary\":\"done\",\"items\":[";
        String[] invalid = {
                prefix + "{\"artifactId\":\"artifact-1\",\"artifactId\":\"artifact-2\",\"version\":\"1\"}]}",
                prefix + "{\"artifactId\":\"artifact-1\",\"version\":\"1\",\"objectId\":\"forged\"}]}",
                "{\"runId\":\"" + RUN
                        + "\",\"workItemId\":\"work-1\",\"expectedTaskVersion\":1,"
                        + "\"expectedWorkItemVersion\":\"2\",\"leaseToken\":\"lease-token\","
                        + "\"summary\":\"done\",\",\"items\":[" + validItem + "]}",
                prefix + validItem + "]}{}"
        };
        for (int index = 0; index < invalid.length; index++) {
            mvc.perform(post("/agent/tasks/task-1/deliveries")
                            .principal(principal()).header(HttpHeaders.AUTHORIZATION, BEARER)
                            .header("Idempotency-Key", "delivery-invalid-key-" + index)
                            .contentType("application/json").content(invalid[index]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("OUTPUT_REQUEST_INVALID"));
        }
        verify(service, never()).submit(any(), anyString(), anyString(), anyString(),
                any(), anyString());
    }

    @Test
    void exactServiceStatusBodyAndRequestIdArePreserved() throws Exception {
        when(service.submit(any(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new TaskDeliveryHttpResult(200,
                        "{\"code\":\"E0\",\"data\":{\"deliveryId\":\"delivery-1\","
                                + "\"taskId\":\"task-1\",\"revision\":\"1\",\"version\":\"0\","
                                + "\"taskVersion\":\"2\",\"state\":\"SUBMITTED\","
                                + "\"summary\":\"done\",\"items\":[],\"reviewActions\":[]}}"));
        mvc.perform(post("/agent/tasks/task-1/deliveries")
                        .principal(principal()).header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "delivery-submit-key-0001")
                        .header("X-Request-ID", "delivery-request-1")
                        .contentType("application/json").content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", "delivery-request-1"))
                .andExpect(jsonPath("$.data.deliveryId").value("delivery-1"));
    }

    @Test
    void missingTicketPrincipalFailsClosedBeforeService() throws Exception {
        mvc.perform(post("/agent/tasks/task-1/deliveries")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "delivery-submit-key-0002")
                        .contentType("application/json").content(validBody()))
                .andExpect(status().isUnauthorized());
        verify(service, never()).submit(any(), anyString(), anyString(), anyString(),
                any(), anyString());
    }

    private String validBody() {
        return "{\"runId\":\"" + RUN
                + "\",\"workItemId\":\"work-1\",\"expectedTaskVersion\":\"1\","
                + "\"expectedWorkItemVersion\":\"2\",\"leaseToken\":\"lease-token\","
                + "\"summary\":\"done\",\"items\":[{\"artifactId\":\"artifact-1\","
                + "\"version\":\"1\",\"purpose\":\"conclusion\"}]}";
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
