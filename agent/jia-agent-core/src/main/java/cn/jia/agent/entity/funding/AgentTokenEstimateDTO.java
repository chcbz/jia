package cn.jia.agent.entity.funding;

import java.io.Serializable;

public record AgentTokenEstimateDTO(
        String input,
        String cachedInput,
        String output,
        String reasoning) implements Serializable {
}
