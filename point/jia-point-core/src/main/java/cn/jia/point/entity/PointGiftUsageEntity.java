package cn.jia.point.entity;

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
 * @since 2021-02-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("point_gift_usage")
@Schema(name = "PointGiftUsage对象", description="")
public class PointGiftUsageEntity extends BaseEntity {

    @Serial
    private static final long serialVersionUID=1L;

    @Schema(description = "礼品兑换ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @Schema(description = "礼品ID")
    private Long giftId;

    @Schema(description = "礼品名称")
    private String name;

    @Schema(description = "礼品描述")
    private String description;

    @Schema(description = "图片地址")
    private String picUrl;

    @Schema(description = "Jia账号")
    private String jiacn;

    @Schema(description = "兑换数量")
    private Integer quantity;

    @Schema(description = "所需积分")
    private Integer point;

    @Schema(description = "消费金额（单位：分）")
    private Integer price;

    @Schema(description = "收货人")
    private String consignee;

    @Schema(description = "收货电话")
    private String phone;

    @Schema(description = "收货地址")
    private String address;

    @Schema(description = "虚拟卡号")
    private String cardNo;

    @Schema(description = "状态 0未支付 1已支付 2已发货 3已收货 4已完成 5已取消")
    private Integer status;

}
