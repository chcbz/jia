package cn.jia.agent.output.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Public lease view. Internal task, Agent and attempt metadata are intentionally omitted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutputLeaseDTO(
        String workItemId,
        String status,
        String version,
        String leaseToken,
        String leaseUntil) {
}
