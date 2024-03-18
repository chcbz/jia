package cn.jia.wx.service;

import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.entity.PayOrderParseVO;

/**
 * @author chc
 */
public interface PayOrderParseService {
    /**
     * 扫码支付结果
     *
     * @return 支付结果
     */
    PayOrderEntity scanPayNotifyResult(PayOrderParseVO payOrderParseVO);

    /**
     * 支付结果处理
     */
    void orderNotifyResult(PayOrderParseVO payOrderParseVO);


}