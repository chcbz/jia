package cn.jia.sms.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.sms.entity.SmsCode;
import cn.jia.sms.mapper.SmsCodeMapper;
import cn.jia.sms.service.ISmsCodeService;
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
public class SmsCodeServiceImpl extends BaseServiceImpl<SmsCodeMapper, SmsCode> implements ISmsCodeService {

}
