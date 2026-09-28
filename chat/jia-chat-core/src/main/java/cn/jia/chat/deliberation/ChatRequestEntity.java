package cn.jia.chat.deliberation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("chat_request")
public class ChatRequestEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String ownerJiacn;
    private String clientId;
    private String requestId;
    private Long requestRevision;
    private String requestDigest;
    private String conversationId;
    private Long conversationGeneration;
    private Long userMessageId;
    private String aggregateState;
    private Long stateVersion;
    private Long createdAt;
    private Long updatedAt;
}
