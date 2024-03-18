package cn.jia.wx.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayOrderParseVO {
    /**
     * 产品ID
     */
    private String productId;
    /**
     * 订单号
     */
    private String outTradeNo;
    /**
     * 用户ID
     */
    private String userId;
}
