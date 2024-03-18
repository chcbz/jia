package cn.jia.wx.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.wx.entity.MpInfoEntity;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-01-09
 */
public interface MpInfoDao extends IBaseDao<MpInfoEntity> {

    MpInfoEntity findByKey(String key);

}
