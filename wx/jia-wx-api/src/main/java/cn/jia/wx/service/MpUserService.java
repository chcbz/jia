package cn.jia.wx.service;

import cn.jia.common.service.IBaseService;
import cn.jia.wx.entity.MpUserEntity;

import java.util.List;

public interface MpUserService extends IBaseService<MpUserEntity> {
    MpUserEntity findByOpenId(String openId);

    MpUserEntity findByJiacn(String jiacn);

    void sync(List<MpUserEntity> userList);

    int unsubscribe(MpUserEntity example);
}
