package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.dao.SmsConfigDao;
import cn.jia.sms.entity.SmsConfigEntity;
import cn.jia.sms.mapper.SmsConfigMapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
@Named
public class SmsConfigDaoImpl extends BaseDaoImpl<SmsConfigMapper, SmsConfigEntity> implements SmsConfigDao {

    @Override
    public int reduce(String clientId) {
        return baseMapper.reduce(clientId);
    }
}
