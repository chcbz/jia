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
@TableName("sms_buy")
@Schema(name = "SmsBuy对象", description="")
public class SmsBuyEntity extends BaseEntity {

    private static final long serialVersionUID=1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "应用标识符")
    private String clientId;

    @Schema(description = "充值数量")
    private Integer number;

    @Schema(description = "充值金额")
    private BigDecimal money;

    @Schema(description = "历史充值数量")
    private Integer total;

    @Schema(description = "剩余数量")
    private Integer remain;

    @Schema(description = "时间")
    private Long time;

    @Schema(description = "状态 0未支付 1已支付 2已取消")
    private Integer status;


}
