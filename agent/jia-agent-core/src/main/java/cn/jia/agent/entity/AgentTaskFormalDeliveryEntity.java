package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Immutable delivery batch identity plus its single authoritative review state. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_task_formal_delivery")
public class AgentTaskFormalDeliveryEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String workItemId;
    private String deliveryId;
    private Long revision;
    private String supersedesDeliveryId;
    private String producerAgentId;
    private String runId;
    private String summary;
    private String state;
    private String manifestArtifactId;
    private Integer manifestArtifactVersion;
    private Long submittedAt;
    private String reviewedByJiacn;
    private String reviewReason;
    private Long reviewedAt;
    private Long version;
}
