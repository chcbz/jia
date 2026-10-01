package cn.jia.chat.archive.maintenance.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ArchiveAdminOperationDTO(String operationId, String key, String state,
        String method, String path, String collectionId, String jobId, String draftId,
        String action, String authorizationRevision, Map<String, Object> result) {
    public ArchiveAdminOperationDTO {
        result = result == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }
}
