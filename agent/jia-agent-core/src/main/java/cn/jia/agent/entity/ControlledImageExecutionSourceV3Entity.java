package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import java.io.Serial;

/** Immutable server-resolved source descriptor for a protocol-v3 execution. */
@Data @EqualsAndHashCode(callSuper=true) @Accessors(chain=true)
@TableName("agent_controlled_image_execution_source_v3")
public class ControlledImageExecutionSourceV3Entity extends BaseEntity {
    @Serial private static final long serialVersionUID=1L;
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String ownerJiacn; private String executionId; private String inputRef; private Integer inputOrdinal;
    private String sourceKind; private String contentMimeType; private Long byteLength; private String contentSha256;
    private String sourceJson; private String fileId; private Integer fileVersion; private String purpose;
    private String conversationId; private Long conversationGeneration; private String assetId; private Long assetRevision;
    private String producerRequestId; private Long producerRequestRevision; private String producerStepId;
    private String producerExecutionId; private String producerRunId; private String producerOutputId;
    private Long createdAt;
}
