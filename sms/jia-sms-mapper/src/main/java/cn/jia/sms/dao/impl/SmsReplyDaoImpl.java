package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.dao.SmsReplyDao;
import cn.jia.sms.entity.SmsReplyEntity;
import cn.jia.sms.mapper.SmsReplyMapper;
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
public class SmsReplyDaoImpl extends BaseDaoImpl<SmsReplyMapper, SmsReplyEntity> implements SmsReplyDao {
}
