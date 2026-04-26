package cn.jia.wx.service;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.StringUtil;
import cn.jia.wx.common.WxErrorConstants;
import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.entity.PayOrderParseVO;
import jakarta.inject.Named;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * GraalVM Native Image 兼容的支付订单解析器
 * @author chc
 */
@Named
public class PayOrderParse implements PayOrderParseService {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    // 静态处理器映射表（编译时初始化）
    private static final java.util.Map<String, Class<? extends PayOrderParseService>> HANDLERS;
    
    static {
        HANDLERS = new java.util.HashMap<>();
        // 静态映射表为空，所有处理器都通过 AbstractPayOrderParseService.HANDLER 动态注册
    }
    
    /**
     * 获取支付订单解析服务实例
     * @param payOrderParseVO 解析请求
     * @return 解析服务
     */
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
        
        // GraalVM Native Image 兼容：使用静态映射表
        Class<? extends PayOrderParseService> handlerClass = HANDLERS.get(prefix);
        if (handlerClass != null) {
            return applicationContext.getBean(handlerClass);
        }
        
        // 如果 prefix 不在静态映射中，尝试从 AbstractPayOrderParseService 获取
        String className = AbstractPayOrderParseService.HANDLER.get(prefix);
        if (className != null) {
            try {
                Class<?> cls = Class.forName(className);
                return (PayOrderParseService) applicationContext.getBean(cls);
            } catch (Exception e) {
                // 静默处理
            }
        }
        
        throw new EsRuntimeException(WxErrorConstants.WXPAY_ORDER_HANDLER_ISNULL);
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