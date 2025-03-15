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
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PayInfoServiceImpl extends BaseServiceImpl<PayInfoDao, PayInfoEntity> implements PayInfoService {
    private final Map<String, WxPayService> wxPayServiceMap = new HashMap<>(16);

//    @PostConstruct
    public void init() {
        List<PayInfoEntity> payInfoList = baseDao.selectAll();
        for(PayInfoEntity pay : payInfoList) {
            WxPayService wxPayService = new WxPayServiceImpl();
            WxPayConfig config = new WxPayConfig();
            BeanUtil.copyPropertiesIgnoreNull(pay, config);
            wxPayService.setConfig(config);
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
                wxPayService = new WxPayServiceImpl();
                WxPayConfig config = new WxPayConfig();
                BeanUtil.copyPropertiesIgnoreNull(info, config);
                wxPayService.setConfig(config);
                wxPayServiceMap.put(info.getAppId(), wxPayService);
            }
        }
        if(wxPayService == null) {
            throw new EsRuntimeException(WxErrorConstants.WXMP_NOT_EXIST);
        }
        return wxPayService;
    }

    @Override
    public PayInfoEntity findByKey(String key) {
        PayInfoEntity payInfoEntity = new PayInfoEntity();
        payInfoEntity.setAppId(key);
        return baseDao.selectByEntity(payInfoEntity).stream().findFirst().orElse(null);
    }
}