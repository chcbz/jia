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
@TableName("sms_send")
@Schema(name = "SmsSend对象", description="")
public class SmsSendEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "应用标识符")
    private String clientId;

    @Schema(description = "手机号码")
    private String mobile;

    @Schema(description = "短信内容")
    private String content;

    @Schema(description = "小号")
    private String xh;

    @Schema(description = "消息编号")
    @TableId(value = "msgid", type = IdType.AUTO)
    private String msgid;


}
