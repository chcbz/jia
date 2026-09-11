package cn.jia.agent.output.dto;

public record OutputUploadCreateDTO(
        String runId,
        OutputSourceDTO source,
        String name,
        String size,
        String sha256,
        String mime) { }
