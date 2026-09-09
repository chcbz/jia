package cn.jia.agent.output.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("output_run_binding")
public class OutputRunBindingEntity {
    private String tenantId;
    private String clientId;
    private String runId;
    private String sourceType;
    private String sourceId;
    private String producerAgentId;
    private String bindingId;
    private String originalRuntimeId;
    private String originType;
    private String originId;
    private String state;
    private Integer policyVersion;
    private Long recoveryUntil;
    private Long maxBytes;
    private Integer maxFiles;
    private String workItemId;
    private Long createdAt;
    private Long updatedAt;
    private Long rowVersion;
}
