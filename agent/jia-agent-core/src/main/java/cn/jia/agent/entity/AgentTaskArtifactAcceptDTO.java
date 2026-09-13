package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Atomic F06 decision: accept one exact artifact version and supersede explicit conflicts. */
@Data
public class AgentTaskArtifactAcceptDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Stable caller-generated idempotency identity, scoped to tenant/client/task. */
    private String decisionId;
    private AgentTaskArtifactRefDTO acceptedArtifact;
    private List<AgentTaskArtifactRefDTO> supersededArtifacts;
}
