package cn.jia.sms.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.sms.entity.SmsSend;
import cn.jia.sms.mapper.SmsSendMapper;
import cn.jia.sms.service.ISmsSendService;
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
public class SmsSendServiceImpl extends BaseServiceImpl<SmsSendMapper, SmsSend> implements ISmsSendService {

}
