package cn.jia.agent.output.dto;

import java.util.List;

/** Strict scalar request for the single authoritative formal-delivery transaction. */
public record TaskDeliverySubmitDTO(
        String runId, String workItemId,
        String expectedTaskVersion, String expectedWorkItemVersion,
        String leaseToken, String summary, List<TaskDeliveryItemDTO> items) { }
