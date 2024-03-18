package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.dao.SmsSendDao;
import cn.jia.sms.entity.SmsSendEntity;
import cn.jia.sms.entity.SmsSendVO;
import cn.jia.sms.mapper.SmsSendMapper;
import jakarta.inject.Named;

import java.util.List;
import java.util.Map;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-14
 */
@Named
public class SmsSendDaoImpl extends BaseDaoImpl<SmsSendMapper, SmsSendEntity> implements SmsSendDao {
    @Override
    public List<Map<String, Object>> groupByMobile(SmsSendVO example) {
        return baseMapper.groupByMobile(example);
    }
}
