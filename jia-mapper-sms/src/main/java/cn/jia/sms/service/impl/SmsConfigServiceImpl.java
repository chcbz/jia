package cn.jia.sms.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.sms.entity.SmsConfig;
import cn.jia.sms.mapper.SmsConfigMapper;
import cn.jia.sms.service.ISmsConfigService;
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
public class SmsConfigServiceImpl extends BaseServiceImpl<SmsConfigMapper, SmsConfig> implements ISmsConfigService {

}
