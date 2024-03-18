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
@TableName("sms_config")
@Schema(name = "SmsConfig对象", description="")
public class SmsConfigEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "应用标识符")
    @TableId(value = "client_id", type = IdType.AUTO)
    private String clientId;

    @Schema(description = "简称")
    private String shortName;

    @Schema(description = "短信回复回调地址")
    private String replyUrl;

    @Schema(description = "剩余可用数量")
    private Integer remain;


}
