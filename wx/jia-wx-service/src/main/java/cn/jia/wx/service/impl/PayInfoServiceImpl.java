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

    @Value("${wx.external-http.connection-request-timeout-ms:0}")
    private int connectionRequestTimeoutMillis;

    @Value("${wx.external-http.connect-timeout-ms:0}")
    private int connectTimeoutMillis;

    @Value("${wx.external-http.read-timeout-ms:0}")
    private int readTimeoutMillis;


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
        WxPayService wxPayService = new WxPayServiceImpl();
        WxPayConfig config = new WxPayConfig();
        BeanUtil.copyPropertiesIgnoreNull(info, config);
        int configuredConnectionTimeout = configuredConnectionTimeout();
        if (configuredConnectionTimeout > 0) {
            config.setHttpConnectionTimeout(configuredConnectionTimeout);
        }
        if (readTimeoutMillis > 0) {
            config.setHttpTimeout(readTimeoutMillis);
        }
        wxPayService.setConfig(config);
        return wxPayService;
    }

    private int configuredConnectionTimeout() {
        if (connectionRequestTimeoutMillis > 0 && connectTimeoutMillis > 0) {
            return Math.min(connectionRequestTimeoutMillis, connectTimeoutMillis);
        }
        return Math.max(connectionRequestTimeoutMillis, connectTimeoutMillis);
    }

    @Override
    public PayInfoEntity findByKey(String key) {
        PayInfoEntity payInfoEntity = new PayInfoEntity();
        payInfoEntity.setAppId(key);
        return baseDao.selectByEntity(payInfoEntity).stream().findFirst().orElse(null);
    }
}