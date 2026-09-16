package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Read model returned by the R2 transactional submission boundary, including safe replay. */
@Data
public class AgentTaskFormalDeliveryViewDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String workItemId;
    private String deliveryId;
    private Long revision;
    private String state;
    private String runId;
    private String producerAgentId;
    private String summary;
    private String manifestArtifactId;
    private Integer manifestArtifactVersion;
    private Long submittedAt;
    private Long workItemVersion;
    private Long taskVersion;
    private Boolean replayed;
    private List<AgentTaskFormalDeliveryItemDTO> items;
}
