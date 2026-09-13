package cn.jia.wx.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.BeanUtil;
import cn.jia.core.util.StringUtil;
import cn.jia.wx.common.WxErrorConstants;
import cn.jia.wx.dao.PayInfoDao;
import cn.jia.wx.entity.PayInfoEntity;
import cn.jia.wx.service.PayInfoService;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PayInfoServiceImpl extends BaseServiceImpl<PayInfoDao, PayInfoEntity> implements PayInfoService {
    private final Map<String, WxPayService> wxPayServiceMap = new HashMap<>(16);

    @Value("${wx.external-http.connection-request-timeout-ms:250}")
    private int connectionRequestTimeoutMillis = 250;

    @Value("${wx.external-http.connect-timeout-ms:500}")
    private int connectTimeoutMillis = 500;

    @Value("${wx.external-http.read-timeout-ms:1750}")
    private int readTimeoutMillis = 1750;

    @Value("${wx.external-http.total-timeout-ms:2500}")
    private int totalTimeoutMillis = 2500;

//    @PostConstruct
    public void init() {
        List<PayInfoEntity> payInfoList = baseDao.selectAll();
        for(PayInfoEntity pay : payInfoList) {
            WxPayService wxPayService = createWxPayService(pay);
            wxPayServiceMap.put(pay.getAppId(), wxPayService);
        }
    }

    @Override
    public WxPayService findWxPayService(HttpServletRequest request) {
        String appid = request.getParameter("appid");
        if(StringUtil.isEmpty(appid)) {
            throw new EsRuntimeException(WxErrorConstants.APPID_NOT_NULL);
        }
        return findWxPayService(appid);
    }

    @Override
    public WxPayService findWxPayService(String key) {
        if(StringUtil.isEmpty(key)) {
            throw new EsRuntimeException(WxErrorConstants.APPID_NOT_NULL);
        }
        WxPayService wxPayService = wxPayServiceMap.get(key);
        if(wxPayService == null) {
            PayInfoEntity info = findByKey(key);
            if(info != null) {
                wxPayService = createWxPayService(info);
                wxPayServiceMap.put(info.getAppId(), wxPayService);
            }
        }
        if(wxPayService == null) {
            throw new EsRuntimeException(WxErrorConstants.WXMP_NOT_EXIST);
        }
        return wxPayService;
    }

    private WxPayService createWxPayService(PayInfoEntity info) {
        if (connectionRequestTimeoutMillis <= 0 || connectTimeoutMillis <= 0 || readTimeoutMillis <= 0
                || totalTimeoutMillis <= 0
                || (long) connectionRequestTimeoutMillis + connectTimeoutMillis + readTimeoutMillis > totalTimeoutMillis) {
            throw new IllegalStateException("Wx pay HTTP phase timeouts must fit the total timeout budget");
        }
        WxPayService wxPayService = new WxPayServiceImpl();
        WxPayConfig config = new WxPayConfig();
        BeanUtil.copyPropertiesIgnoreNull(info, config);
        config.setHttpConnectionTimeout(Math.min(connectionRequestTimeoutMillis, connectTimeoutMillis));
        config.setHttpTimeout(readTimeoutMillis);
        wxPayService.setConfig(config);
        return wxPayService;
    }

    @Override
    public PayInfoEntity findByKey(String key) {
        PayInfoEntity payInfoEntity = new PayInfoEntity();
        payInfoEntity.setAppId(key);
        return baseDao.selectByEntity(payInfoEntity).stream().findFirst().orElse(null);
    }
}