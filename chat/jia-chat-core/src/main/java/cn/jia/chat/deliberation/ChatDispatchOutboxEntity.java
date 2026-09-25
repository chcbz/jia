package cn.jia.chat.deliberation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("chat_dispatch_outbox")
public class ChatDispatchOutboxEntity {
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;
    private String tenantId;
    private String ownerJiacn;
    private String clientId;
    private String turnId;
    private String dispatchId;
    private String eventType;
    private String status;
    private String payloadJson;
    private Long version;
    private Long createdAt;
    private Long updatedAt;
}
