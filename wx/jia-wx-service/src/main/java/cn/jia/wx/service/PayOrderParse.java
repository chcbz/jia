package cn.jia.wx.service;

import cn.jia.core.config.SpringContextHolder;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.StringUtil;
import cn.jia.wx.common.WxErrorConstants;
import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.entity.PayOrderParseVO;
import jakarta.inject.Named;

/**
 * @author chc
 */
@Named
public class PayOrderParse implements PayOrderParseService {
    public PayOrderParseService instance(PayOrderParseVO payOrderParseVO) {
        String prefix = null;
        if (StringUtil.isNotEmpty(payOrderParseVO.getOutTradeNo())) {
            prefix = payOrderParseVO.getOutTradeNo().substring(0, 3);
        } else if (StringUtil.isNotEmpty(payOrderParseVO.getProductId())) {
            prefix = payOrderParseVO.getProductId().substring(0, 3);
        }
        if (StringUtil.isEmpty(prefix)) {
            throw new EsRuntimeException(WxErrorConstants.WXPAY_TYPE_ISNULL);
        }
        // 取得Class对象
        PayOrderParseService parseService;
        try {
            Class<?> cls = Class.forName(AbstractPayOrderParseService.HANDLER.get(prefix));
            parseService = (PayOrderParseService) SpringContextHolder.getBean(cls);
        } catch (Exception e) {
            throw new EsRuntimeException(WxErrorConstants.WXPAY_ORDER_HANDLER_ISNULL);
        }
        return parseService;
    }

    @Override
    public PayOrderEntity scanPayNotifyResult(PayOrderParseVO payOrderParseVO) {
        return instance(payOrderParseVO).scanPayNotifyResult(payOrderParseVO);
    }

    @Override
    public void orderNotifyResult(PayOrderParseVO payOrderParseVO) {
        instance(payOrderParseVO).orderNotifyResult(payOrderParseVO);
    }
}