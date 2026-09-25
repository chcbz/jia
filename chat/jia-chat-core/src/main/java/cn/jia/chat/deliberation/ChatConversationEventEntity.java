package cn.jia.chat.deliberation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("chat_conversation_event")
public class ChatConversationEventEntity {
    @TableId(value = "event_sequence", type = IdType.AUTO)
    private Long eventSequence;
    private String eventId;
    private String tenantId;
    private String ownerJiacn;
    private String clientId;
    private String conversationId;
    private Long conversationGeneration;
    private String requestId;
    private String turnId;
    private String dispatchId;
    private String eventType;
    private Long eventVersion;
    private String payloadJson;
    private Long occurredAt;
}
