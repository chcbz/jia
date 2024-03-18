package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.dao.SmsTemplateDao;
import cn.jia.sms.entity.SmsTemplateEntity;
import cn.jia.sms.mapper.SmsTemplateMapper;
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
public class SmsTemplateDaoImpl extends BaseDaoImpl<SmsTemplateMapper, SmsTemplateEntity> implements SmsTemplateDao {
}
