package cn.jia.agent.output.dto;

/** Exact immutable artifact version selected for a formal task delivery. */
public record TaskDeliveryItemDTO(String artifactId, String version, String purpose) { }
