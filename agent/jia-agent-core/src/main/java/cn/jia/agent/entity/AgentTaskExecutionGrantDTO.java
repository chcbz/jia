package cn.jia.agent.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Browser-safe projection of a persisted task execution authorization fact. */
@Data
@Accessors(chain = true)
public class AgentTaskExecutionGrantDTO implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String grantId;
    private String taskId;
    private Long requirementRevision;
    private Long assignmentRevision;
    private String targetAgentId;
    private List<String> permittedOperations;
    private List<InputSummary> inputs;
    private String state;
    private Long grantVersion;
    private Boolean paidExecutionAuthorized;
    private Long createdAt;
    private Long revokedAt;

    public record InputSummary(String fileId, int version, String purpose,
            String contentMimeType, long byteLength, String contentHash) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
    }
}
