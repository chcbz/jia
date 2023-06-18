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
@TableName("sms_code")
@Schema(name = "SmsCode对象", description="")
public class SmsCode extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "应用标识码")
    private String clientId;

    @Schema(description = "手机号码")
    private String phone;

    @Schema(description = "短信验证码")
    private String smsCode;

    @Schema(description = "短信类型  1登录 2忘记密码")
    private Integer smsType;

    @Schema(description = "时间")
    private Long time;

    @Schema(description = "发送次数")
    private Integer count;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;


}
