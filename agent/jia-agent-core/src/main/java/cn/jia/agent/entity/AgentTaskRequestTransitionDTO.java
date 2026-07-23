package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class AgentTaskRequestTransitionDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long expectedVersion;
    /** Structured response; required for resolve/reject and optional for acknowledge. */
    private Map<String, Object> response;
}
