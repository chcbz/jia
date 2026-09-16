package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Bounded command for the first R2 formal delivery revision.
 *
 * <p>{@code deliveryId} is an externally stable replay identity. HTTP adapters must derive it
 * from their validated idempotency authority rather than accept a second client-supplied key.</p>
 */
@Data
public class AgentTaskFormalDeliverySubmitDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String deliveryId;
    private String runId;
    private String workItemId;
    private String leaseToken;
    private Long expectedTaskVersion;
    private Long expectedWorkItemVersion;
    private String summary;
    private String manifestArtifactId;
    private Integer manifestArtifactVersion;
    private List<AgentTaskFormalDeliveryItemDTO> items;
}
