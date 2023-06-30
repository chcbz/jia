package cn.jia.sms.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.sms.entity.SmsMessageEntity;
import cn.jia.sms.mapper.SmsMessageMapper;
import cn.jia.sms.service.ISmsMessageService;
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
public class SmsMessageServiceImpl extends BaseServiceImpl<SmsMessageMapper, SmsMessageEntity> implements ISmsMessageService {

}
