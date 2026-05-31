package cn.jia.wx.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.wx.entity.MpUserEntity;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-01-09
 */
public interface MpUserDao extends IBaseDao<MpUserEntity> {
    int unsubscribe(MpUserEntity example);
}
