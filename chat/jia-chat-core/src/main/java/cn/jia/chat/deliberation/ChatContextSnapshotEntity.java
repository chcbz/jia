package cn.jia.chat.deliberation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("chat_context_snapshot")
public class ChatContextSnapshotEntity {
    @TableId(value = "snapshot_id", type = IdType.INPUT)
    private String snapshotId;
    private String tenantId;
    private String ownerJiacn;
    private String clientId;
    private String conversationId;
    private Long conversationGeneration;
    private String requestId;
    private Long requestRevision;
    private String targetAgentId;
    private String route;
    private String sourceVectorJson;
    private String factsManifestJson;
    private String contextDigest;
    private Long createdAt;
}
