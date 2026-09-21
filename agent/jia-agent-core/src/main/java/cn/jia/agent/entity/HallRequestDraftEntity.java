package cn.jia.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/** Owner-scoped, non-executable Hall request draft. User text must never be logged. */
@Data
@Accessors(chain = true)
@TableName("hall_request_draft")
public class HallRequestDraftEntity implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    @TableId(value = "draft_id", type = IdType.INPUT)
    private String draftId;
    private String tenantId;
    private String clientId;
    private String ownerJiacn;
    private String kind;
    private String originRef;
    private String sourceType;
    private String sourceId;
    private Integer sourceVersion;
    private String caseId;
    private String taskId;
    private String conversationId;
    private String title;
    private String instruction;
    private String targetAgentId;
    private String outputMime;
    private String inputsJson;
    private String sourceOutputRefJson;
    private String uiCheckpointJson;
    private Long revision;
    private String state;
    private String submissionRef;
    private String submittedExecutionId;
    private String createKey;
    private String createHash;
    private String submitKey;
    private String submitHash;
    private String discardKey;
    private String discardHash;
    private Long createdAt;
    private Long updatedAt;
}
