package cn.jia.chat.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * <p>
 * 聊天消息
 * </p>
 *
 * @author chc
 * @since 2026-04-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("chat_message")
@Schema(name = "ChatMessage对象", description = "聊天消息")
public class ChatMessageEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "消息类型（USER/ASSISTANT/SYSTEM）")
    private String messageType;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "消息元数据（JSON格式）")
    private String metadata;

    @Schema(description = "用户ID（冗余字段，便于长效记忆汇总）")
    private String jiacn;
}
