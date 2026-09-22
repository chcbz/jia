package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_execution_report_inbox")
public class AgentExecutionReportEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String reportId;
    private String ownerJiacn;
    private String agentId;
    private String runtimeInstanceId;
    private String messageType;
    private String messageId;
    private String commandId;
    private String dispatchMessageId;
    private String executionRef;
    private Long grantRevision;
    private Integer attempt;
    private String fencingToken;
    private Long sequence;
    private Long occurredAt;
    private byte[] semanticHash;
    private byte[] payloadJson;
    private String resultRef;
    private Long committedVersion;
}
