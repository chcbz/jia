package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.dao.SmsMessageDao;
import cn.jia.sms.entity.SmsMessageEntity;
import cn.jia.sms.mapper.SmsMessageMapper;
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
public class SmsMessageDaoImpl extends BaseDaoImpl<SmsMessageMapper, SmsMessageEntity> implements SmsMessageDao {

}
