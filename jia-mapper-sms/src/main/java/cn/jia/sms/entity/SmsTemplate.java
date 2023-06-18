package cn.jia.sms.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

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
@TableName("sms_template")
@Schema(name = "SmsTemplate对象", description="")
public class SmsTemplate extends BaseEntity {

    private static final long serialVersionUID=1L;

    @Schema(description = "模板ID")
    @TableId(value = "template_id", type = IdType.AUTO)
    private String templateId;

    @Schema(description = "应用标识码")
    private String clientId;

    @Schema(description = "模板名称")
    private String name;

    @Schema(description = "模板标题")
    private String title;

    @Schema(description = "模板内容")
    private String content;

    @Schema(description = "消息类型 1微信 2邮件 3短信")
    private Integer msgType;

    @Schema(description = "模板类型")
    private Integer type;

    @Schema(description = "状态 0待审核 1审核通过 2审核失败")
    private Integer status;


}
