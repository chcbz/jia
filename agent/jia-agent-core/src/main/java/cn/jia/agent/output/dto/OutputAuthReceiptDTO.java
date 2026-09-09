package cn.jia.agent.output.dto;

import java.io.Serializable;
import java.util.List;

public record OutputAuthReceiptDTO(
        String causationId,
        String runId,
        String token,
        String expiresAt,
        List<String> operations) implements Serializable {
    public OutputAuthReceiptDTO {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
