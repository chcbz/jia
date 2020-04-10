package cn.jia.wx.service;

import cn.jia.wx.common.Constants;
import cn.jia.wx.entity.PayOrder;

public abstract class PayOrderParse {

    protected String productId;

    public abstract PayOrder scanPayNotifyResult();

    public abstract void orderNotifyResult();

    public static PayOrderParse instance(String productId) throws Exception{
        String prefix = productId.substring(0, 3);
        Class<?> cls = Class.forName(Constants.WX_PAY_ORDER_PARSE.get(prefix)); // 取得Class对象
        PayOrderParse payOrderParse = (PayOrderParse)cls.newInstance();
        payOrderParse.productId = productId;
        return payOrderParse;
    }
}
