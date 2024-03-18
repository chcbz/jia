package cn.jia.point.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.point.dao.PointSignDao;
import cn.jia.point.entity.PointSignEntity;
import cn.jia.point.mapper.PointSignMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

@Named
public class PointSignDaoImpl extends BaseDaoImpl<PointSignMapper, PointSignEntity> implements PointSignDao {
    @Override
    public PointSignEntity selectLatest(String jiacn) {
        LambdaQueryWrapper<PointSignEntity> queryWrapper = Wrappers.lambdaQuery(new PointSignEntity());
        return baseMapper.selectOne(queryWrapper.eq(PointSignEntity::getJiacn, jiacn)
                .orderByDesc(PointSignEntity::getUpdateTime));
    }
}
