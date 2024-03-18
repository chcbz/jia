package cn.jia.wx.service;

import cn.jia.common.service.IBaseService;
import cn.jia.wx.entity.MpInfoEntity;
import jakarta.servlet.http.HttpServletRequest;
import me.chanjar.weixin.mp.api.WxMpService;

public interface MpInfoService extends IBaseService<MpInfoEntity> {
    WxMpService findWxMpService(HttpServletRequest request);

    WxMpService findWxMpService(String key);

    MpInfoEntity findByKey(String key);
}
