package cn.jia.sms.entity;

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
 * 
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("sms_message")
@Schema(name = "SmsMessage对象", description="")
public class SmsMessageEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "模板ID")
    private String templateId;

    @Schema(description = "发送者")
    private String sender;

    @Schema(description = "接收者")
    private String receiver;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "链接地址")
    private String url;

    @Schema(description = "消息类型 1微信 2邮件 3短息")
    private Integer msgType;

    @Schema(description = "状态 0未发送 1已发送")
    private Integer status;


}
