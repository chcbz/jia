package cn.jia.wx.service;

import cn.jia.common.service.IBaseService;
import cn.jia.wx.entity.MpUserEntity;

import java.util.List;

public interface MpUserService extends IBaseService<MpUserEntity> {
    MpUserEntity findByOpenId(String openId);

    MpUserEntity findByAppIdAndOpenId(String appid, String openId);

    MpUserEntity findByJiacn(String jiacn);

    /**
     * Persist the server-observed time of a verified inbound WeChat message. Returns zero when
     * the user record no longer exists.
     */
    int touchLastActive(long id, long lastActiveTime);

    void sync(List<MpUserEntity> userList);

    int unsubscribe(MpUserEntity example);
}
