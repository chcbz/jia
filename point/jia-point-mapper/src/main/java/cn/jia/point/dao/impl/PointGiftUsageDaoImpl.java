package cn.jia.point.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.mapper.PointGiftUsageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import java.util.List;

@Named
public class PointGiftUsageDaoImpl extends BaseDaoImpl<PointGiftUsageMapper, PointGiftUsageEntity>
        implements PointGiftUsageDao {
    @Override
    public List<PointGiftUsageEntity> listByUser(String tenantId, String jiacn) {
        LambdaQueryWrapper<PointGiftUsageEntity> queryWrapper = Wrappers.lambdaQuery(new PointGiftUsageEntity())
                .eq(PointGiftUsageEntity::getTenantId, tenantId)
                .eq(PointGiftUsageEntity::getJiacn, jiacn);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public List<PointGiftUsageEntity> listByGift(String tenantId, Long giftId) {
        LambdaQueryWrapper<PointGiftUsageEntity> queryWrapper = Wrappers.lambdaQuery(new PointGiftUsageEntity())
                .eq(PointGiftUsageEntity::getTenantId, tenantId)
                .eq(PointGiftUsageEntity::getGiftId, giftId);
        return baseMapper.selectList(queryWrapper);
    }


    @Override
    public PointGiftUsageEntity selectScoped(String tenantId, Long id, boolean lock) {
        QueryWrapper<PointGiftUsageEntity> query = new QueryWrapper<PointGiftUsageEntity>()
                .eq("tenant_id", tenantId).eq("id", id);
        if (lock) query.last("FOR UPDATE");
        return baseMapper.selectOne(query);
    }

    @Override
    public int updateScoped(String tenantId, PointGiftUsageEntity entity) {
        entity.setTenantId(tenantId);
        entity.init4Update();
        return baseMapper.update(entity, new UpdateWrapper<PointGiftUsageEntity>()
                .eq("tenant_id", tenantId).eq("id", entity.getId()));
    }

    @Override
    public int deleteScoped(String tenantId, Long id) {
        return baseMapper.delete(new QueryWrapper<PointGiftUsageEntity>()
                .eq("tenant_id", tenantId).eq("id", id));
    }
}
