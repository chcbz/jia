package cn.jia.sms.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.sms.entity.SmsConfigEntity;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
public interface SmsConfigDao extends IBaseDao<SmsConfigEntity> {
    /**
     * 扣减剩余数量
     *
     * @param clientId 应用标识符
     * @return 更新记录数
     */
    int reduce(String clientId);
}
