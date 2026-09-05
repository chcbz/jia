package cn.jia.agent.entity.funding;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskFundingCancelDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String expectedTaskVersion;
}
