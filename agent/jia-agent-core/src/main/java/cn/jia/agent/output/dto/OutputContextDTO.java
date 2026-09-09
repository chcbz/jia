package cn.jia.agent.output.dto;

import java.io.Serializable;
import java.util.List;

public record OutputContextDTO(
        int schemaVersion,
        String runId,
        OutputSourceDTO source,
        String maxFileBytes,
        String maxBatchBytes,
        String manifestRelativePath,
        List<String> capabilities) implements Serializable {
    public OutputContextDTO {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
