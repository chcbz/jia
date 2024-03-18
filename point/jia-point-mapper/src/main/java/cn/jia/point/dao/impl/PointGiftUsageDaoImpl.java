package cn.jia.point.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.mapper.PointGiftUsageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

import java.util.List;

@Named
public class PointGiftUsageDaoImpl extends BaseDaoImpl<PointGiftUsageMapper, PointGiftUsageEntity>
        implements PointGiftUsageDao {
    @Override
    public List<PointGiftUsageEntity> listByUser(String jiacn) {
        LambdaQueryWrapper<PointGiftUsageEntity> queryWrapper = Wrappers.lambdaQuery(new PointGiftUsageEntity())
                .eq(PointGiftUsageEntity::getJiacn, jiacn);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public List<PointGiftUsageEntity> listByGift(Long giftId) {
        LambdaQueryWrapper<PointGiftUsageEntity> queryWrapper = Wrappers.lambdaQuery(new PointGiftUsageEntity())
                .eq(PointGiftUsageEntity::getGiftId, giftId);
        return baseMapper.selectList(queryWrapper);
    }

}
