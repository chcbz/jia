package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Exact immutable artifact version plus its expected outcome-state version. */
@Data
public class AgentTaskArtifactRefDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String artifactId;
    private Integer artifactVersion;
    /** Zero means the artifact is still implicit draft; positive values address persisted outcome state. */
    private Long expectedOutcomeVersion;
}
