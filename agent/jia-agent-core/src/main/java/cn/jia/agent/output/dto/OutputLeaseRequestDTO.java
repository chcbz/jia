package cn.jia.agent.output.dto;

/** Strict scalar request used by the run-ticket work-item lease HTTP adapter. */
public record OutputLeaseRequestDTO(
        String runId,
        String expectedVersion,
        String leaseToken,
        String leaseDurationMillis) {
}
