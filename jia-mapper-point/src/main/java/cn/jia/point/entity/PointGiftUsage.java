package cn.jia.point.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

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
@ApiModel(value="PointGiftUsage对象", description="")
public class PointGiftUsage extends BaseEntity {

    private static final long serialVersionUID=1L;

    @ApiModelProperty(value = "礼品兑换ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "应用标识符")
    private String clientId;

    @ApiModelProperty(value = "礼品ID")
    private Integer giftId;

    @ApiModelProperty(value = "礼品名称")
    private String name;

    @ApiModelProperty(value = "礼品描述")
    private String description;

    @ApiModelProperty(value = "图片地址")
    private String picUrl;

    @ApiModelProperty(value = "Jia账号")
    private String jiacn;

    @ApiModelProperty(value = "兑换数量")
    private Integer quantity;

    @ApiModelProperty(value = "所需积分")
    private Integer point;

    @ApiModelProperty(value = "消费金额（单位：分）")
    private Integer price;

    @ApiModelProperty(value = "收货人")
    private String consignee;

    @ApiModelProperty(value = "收货电话")
    private String phone;

    @ApiModelProperty(value = "收货地址")
    private String address;

    @ApiModelProperty(value = "虚拟卡号")
    private String cardNo;

    @ApiModelProperty(value = "状态 0未支付 1已支付 2已发货 3已收货 4已完成 5已取消")
    private Integer status;

    @ApiModelProperty(value = "兑换时间")
    private Long time;


}
