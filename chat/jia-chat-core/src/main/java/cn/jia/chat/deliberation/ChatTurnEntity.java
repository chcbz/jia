package cn.jia.chat.deliberation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("chat_turn")
public class ChatTurnEntity {
    @TableId(value = "turn_id", type = IdType.INPUT)
    private String turnId;
    private String tenantId;
    private String ownerJiacn;
    private String clientId;
    private String requestId;
    private Long requestRevision;
    private String conversationId;
    private Long conversationGeneration;
    private String targetAgentId;
    private String snapshotId;
    private String contextDigest;
    private String dispatchId;
    private String route;
    private String state;
    private Long stateVersion;
    private Long lastDeltaSeq;
    private String lastDeltaDigest;
    private String finalDigest;
    private Long finalMessageId;
    private String terminalReason;
    private Long createdAt;
    private Long updatedAt;
}
