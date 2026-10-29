package cn.jia.point.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.point.dao.PointGiftDao;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.mapper.PointGiftMapper;
import jakarta.inject.Named;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

/**
 * 短链接 Dao
 *
 * @author chc
 * @since 2023-08-05
 */
@Named
public class PointGiftDaoImpl extends BaseDaoImpl<PointGiftMapper, PointGiftEntity> implements PointGiftDao {

    @Override
    public PointGiftEntity selectScoped(String tenantId, Long id, boolean lock) {
        QueryWrapper<PointGiftEntity> query = new QueryWrapper<PointGiftEntity>()
                .eq("tenant_id", tenantId).eq("id", id);
        if (lock) query.last("FOR UPDATE");
        return baseMapper.selectOne(query);
    }

    @Override
    public int updateScoped(String tenantId, PointGiftEntity entity) {
        entity.setTenantId(tenantId);
        entity.init4Update();
        return baseMapper.update(entity, new UpdateWrapper<PointGiftEntity>()
                .eq("tenant_id", tenantId).eq("id", entity.getId()));
    }

    @Override
    public int deleteScoped(String tenantId, Long id) {
        return baseMapper.delete(new QueryWrapper<PointGiftEntity>()
                .eq("tenant_id", tenantId).eq("id", id));
    }

    @Override
    public java.util.List<PointGiftEntity> listScoped(String tenantId, PointGiftEntity example) {
        example.setTenantId(tenantId);
        QueryWrapper<PointGiftEntity> query = new QueryWrapper<>(example);
        appendQueryWrapper(example, query);
        return baseMapper.selectList(query.eq("tenant_id", tenantId));
    }
}
