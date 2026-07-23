package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * Integration boundary for a future atomic B04 lease-authorized result commit.
 * B06 ordinary artifact publication never mutates work_item.result_artifact_id.
 */
@Data
public class AgentWorkItemResultCommitDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String workItemId;
    private String producerAgentId;
    private String leaseToken;
    private Long expectedWorkItemVersion;
    private AgentTaskArtifactPublishDTO artifact;
}
