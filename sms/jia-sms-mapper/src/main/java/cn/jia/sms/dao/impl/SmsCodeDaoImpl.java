package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.dao.SmsCodeDao;
import cn.jia.sms.entity.SmsCodeEntity;
import cn.jia.sms.mapper.SmsCodeMapper;
import jakarta.inject.Named;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
@Named
public class SmsCodeDaoImpl extends BaseDaoImpl<SmsCodeMapper, SmsCodeEntity> implements SmsCodeDao {
}
