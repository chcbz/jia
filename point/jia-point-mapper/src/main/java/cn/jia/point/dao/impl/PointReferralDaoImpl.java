package cn.jia.point.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.point.dao.PointReferralDao;
import cn.jia.point.entity.PointReferralEntity;
import cn.jia.point.mapper.PointReferralMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

@Named
public class PointReferralDaoImpl extends BaseDaoImpl<PointReferralMapper, PointReferralEntity>
        implements PointReferralDao {
    @Override
    public boolean checkHasReferral(String tenantId, String referral) {
        LambdaQueryWrapper<PointReferralEntity> queryWrapper = Wrappers.lambdaQuery(new PointReferralEntity());
        return !baseMapper.selectList(queryWrapper.eq(PointReferralEntity::getTenantId, tenantId)
                .eq(PointReferralEntity::getReferral, referral).last("LIMIT 1 FOR UPDATE")).isEmpty();
    }
}
