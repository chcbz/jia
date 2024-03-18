package cn.jia.sms.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.sms.entity.SmsBuyEntity;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
public interface SmsBuyDao extends IBaseDao<SmsBuyEntity> {
    SmsBuyEntity selectLatest(String clientId);
}
