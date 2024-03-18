package cn.jia.point.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

public class PointGiftEntityWrapper implements BaseEntityWrapper<PointGiftVO, PointGiftEntity> {
    @Override
    public void appendQueryWrapper(PointGiftVO entity, QueryWrapper<PointGiftEntity> wrapper) {
        LambdaQueryWrapper<PointGiftEntity> queryWrapper = Wrappers.lambdaQuery(new PointGiftEntity());
        if (entity.getCreateTimeStart() != null) {
            queryWrapper.gt(BaseEntity::getCreateTime, entity.getCreateTimeStart());
        }
        if (entity.getCreateTimeEnd() != null) {
            queryWrapper.lt(BaseEntity::getCreateTime, entity.getCreateTimeEnd());
        }
        if (entity.getClientStrictFlag() != null && entity.getClientStrictFlag().equals(1)) {
            if (StringUtils.isNotEmpty(entity.getClientId())) {
                queryWrapper.eq(PointGiftEntity::getClientId, entity.getClientId());
            } else {
                queryWrapper.isNull(PointGiftEntity::getClientId);
            }
        }
        if (StringUtils.isNotEmpty(entity.getNameLike())) {
            queryWrapper.like(PointGiftEntity::getName, entity.getNameLike());
        }
        if (StringUtils.isNotEmpty(entity.getDescriptionLike())) {
            queryWrapper.like(PointGiftEntity::getDescription, entity.getDescriptionLike());
        }
    }

    @Override
    public void appendUpdateWrapper(PointGiftVO entity, UpdateWrapper<PointGiftEntity> wrapper) {

    }
}
