package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/** Immutable, target-independent E05 operation receipt. Lease credentials are never persisted here. */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("agent_work_item_reassignment")
public class AgentWorkItemReassignmentEntity extends BaseEntity {
    @Serial private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String reassignmentId;
    private String requestSha256;
    private String taskId;
    private String workItemId;
    private String operatorSubject;
    private String coordinatorAgentId;
    private String previousAgentId;
    private String targetAgentId;
    private String sourceCommandId;
    private String commandId;
    private String messageId;
    private String outboxEventId;
    private Long expectedWorkItemVersion;
    private Long resultWorkItemVersion;
    private Long taskVersion;
    private String leaseFenceSha256;
    private Long previousLeaseUntil;
    private Long leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
}
