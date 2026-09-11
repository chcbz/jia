package cn.jia.agent.output.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutputUploadDTO(
        String uploadId,
        String state,
        String uploadUrl,
        String expiresAt,
        String objectId,
        String errorCode) {
}
