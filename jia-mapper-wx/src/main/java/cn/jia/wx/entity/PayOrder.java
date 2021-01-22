package cn.jia.wx.entity;

import cn.jia.common.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
 * @since 2021-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("wx_pay_order")
@ApiModel(value="PayOrder对象")
public class PayOrder extends BaseEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @ApiModelProperty(value = "公众账号ID")
    private String appid;

    @ApiModelProperty(value = "商户号")
    private String mchId;

    @ApiModelProperty(value = "用户标识")
    private String openid;

    @ApiModelProperty(value = "商户订单号")
    private String outTradeNo;

    @ApiModelProperty(value = "商品ID")
    private String productId;

    @ApiModelProperty(value = "预支付ID")
    private String prepayId;

    @ApiModelProperty(value = "商品描述")
    private String body;

    @ApiModelProperty(value = "商品详情")
    private String detail;

    @ApiModelProperty(value = "标价金额")
    private Integer totalFee;

    @ApiModelProperty(value = "交易类型")
    private String tradeType;

    @ApiModelProperty(value = "终端IP")
    private String spbillCreateIp;

    @ApiModelProperty(value = "微信支付订单号")
    private String transactionId;

}
