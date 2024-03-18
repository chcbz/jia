package cn.jia.user.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.user.entity.MsgEntity;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
public interface UserMsgDao extends IBaseDao<MsgEntity> {

    int updateByUserId(MsgEntity entity);
}
