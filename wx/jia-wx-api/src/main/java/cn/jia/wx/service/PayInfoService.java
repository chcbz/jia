package cn.jia.wx.service;

import cn.jia.common.service.IBaseService;
import cn.jia.wx.entity.PayInfoEntity;
import com.github.binarywang.wxpay.service.WxPayService;
import jakarta.servlet.http.HttpServletRequest;

public interface PayInfoService extends IBaseService<PayInfoEntity> {
    WxPayService findWxPayService(HttpServletRequest request);

    WxPayService findWxPayService(String key);

    PayInfoEntity findByKey(String key);
}
