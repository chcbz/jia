package cn.jia.agent.output.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("output_source_binding")
public class OutputSourceBindingEntity {
    private String tenantId;
    private String clientId;
    private String sourceType;
    private String sourceId;
    private String ownerJiacn;
    private String ownershipState;
    private Long createdAt;
    private Long updatedAt;
    private Long rowVersion;
}
