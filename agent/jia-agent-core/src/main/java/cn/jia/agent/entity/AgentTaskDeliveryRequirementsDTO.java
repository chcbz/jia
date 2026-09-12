package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class AgentTaskDeliveryRequirementsDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String mode;
    private Integer minFiles;
    private List<String> requiredNames;
    private String instructions;
    private Integer maxReviewRevisions;
}
