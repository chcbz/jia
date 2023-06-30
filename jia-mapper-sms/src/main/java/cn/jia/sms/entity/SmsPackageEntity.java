package cn.jia.sms.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

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
@TableName("sms_package")
@Schema(name = "SmsPackage对象", description="")
public class SmsPackageEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "数量")
    private Integer number;

    @Schema(description = "金额")
    private BigDecimal money;

    @Schema(description = "序列")
    private Integer order;

    @Schema(description = "状态 1有效 0无效")
    private Integer status;


}
