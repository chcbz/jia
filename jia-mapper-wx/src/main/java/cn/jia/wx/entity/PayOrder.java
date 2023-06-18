package cn.jia.wx.entity;

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
 * @since 2021-01-09
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("wx_pay_order")
@Schema(name = "PayOrder对象")
public class PayOrder extends BaseEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @Schema(description = "公众账号ID")
    private String appid;

    @Schema(description = "商户号")
    private String mchId;

    @Schema(description = "用户标识")
    private String openid;

    @Schema(description = "商户订单号")
    private String outTradeNo;

    @Schema(description = "商品ID")
    private String productId;

    @Schema(description = "预支付ID")
    private String prepayId;

    @Schema(description = "商品描述")
    private String body;

    @Schema(description = "商品详情")
    private String detail;

    @Schema(description = "标价金额")
    private Integer totalFee;

    @Schema(description = "交易类型")
    private String tradeType;

    @Schema(description = "终端IP")
    private String spbillCreateIp;

    @Schema(description = "微信支付订单号")
    private String transactionId;

}
