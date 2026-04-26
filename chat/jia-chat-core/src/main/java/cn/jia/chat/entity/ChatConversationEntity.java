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
 * 聊天会话
 * </p>
 *
 * @author chc
 * @since 2026-04-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("chat_conversation")
@Schema(name = "ChatConversation对象", description = "聊天会话")
public class ChatConversationEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "用户ID")
    private String jiacn;

    @Schema(description = "会话状态（ACTIVE/CLOSED）")
    private Integer status;
}
