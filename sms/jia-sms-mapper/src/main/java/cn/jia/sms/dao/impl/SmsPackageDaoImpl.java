package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.dao.SmsPackageDao;
import cn.jia.sms.entity.SmsPackageEntity;
import cn.jia.sms.mapper.SmsPackageMapper;
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
public class SmsPackageDaoImpl extends BaseDaoImpl<SmsPackageMapper, SmsPackageEntity> implements SmsPackageDao {
}
