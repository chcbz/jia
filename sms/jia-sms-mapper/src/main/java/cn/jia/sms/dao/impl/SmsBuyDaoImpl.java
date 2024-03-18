package cn.jia.sms.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.sms.common.SmsConstants;
import cn.jia.sms.dao.SmsBuyDao;
import cn.jia.sms.entity.SmsBuyEntity;
import cn.jia.sms.mapper.SmsBuyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
public class SmsBuyDaoImpl extends BaseDaoImpl<SmsBuyMapper, SmsBuyEntity> implements SmsBuyDao {

    @Override
    public SmsBuyEntity selectLatest(String clientId) {
        LambdaQueryWrapper<SmsBuyEntity> queryWrapper =
                Wrappers.lambdaQuery(SmsBuyEntity.class).ne(SmsBuyEntity::getStatus,
                        SmsConstants.SMS_BUY_STATUS_CANCELED).orderByDesc(SmsBuyEntity::getId);
        return baseMapper.selectOne(queryWrapper);
    }
}
