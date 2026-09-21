package cn.jia.agent.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Browser-query projection for one owner-scoped private execution and its fixed output bindings.
 * Storage locations, instructions, idempotency material and runtime credentials are deliberately absent.
 */
@Data
@Accessors(chain = true)
public class HallExecutionResultRow {
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String executionId;
    private String taskId;
    private String runId;
    private String executionMode;
    private String executionState;

    private String outputId;
    private String workspaceFileId;
    private Integer workspaceFileVersion;
    private String outputOriginalFilename;
    private String outputContentMimeType;
    private Long outputByteLength;
    private String outputContentHash;
    private String outputState;
    private String publicationState;

    private String fileState;
    private String fileOriginalFilename;
    private String fileContentMimeType;
    private Long fileByteLength;
    private String fileContentHash;
}
