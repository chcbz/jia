package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import java.io.Serial;

@Data @EqualsAndHashCode(callSuper=true) @Accessors(chain=true)
@TableName("agent_controlled_image_bridge_operation")
public class ControlledImageBridgeOperationEntity extends BaseEntity {
    @Serial private static final long serialVersionUID=1L;
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String ownerJiacn;
    private String taskId;
    private String assignmentIdempotencyKey;
    private String wrapperDigest;
    private String consentId;
    private Long expectedConsentVersion;
    private String grantId;
    private Long grantVersion;
    private Long assignmentRevision;
    private String authorityLocator;
    private Integer executionProtocolVersion;
    private String operationGrantId;
    private Long createdAt;
}
