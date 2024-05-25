package cn.jia.mat.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class MatNewsEntityWrapper implements BaseEntityWrapper<MatNewsReqVO, MatNewsEntity> {
    @Override
    public void appendQueryWrapper(MatNewsReqVO entity, QueryWrapper<MatNewsEntity> wrapper) {
        wrapper.lambda()
                .like(StringUtil.isNotEmpty(entity.getTitleLike()), MatNewsEntity::getTitle, entity.getTitleLike())
                .ge(entity.getCreateTimeStart() != null, BaseEntity::getCreateTime, entity.getCreateTimeStart())
                .lt(entity.getCreateTimeStart() != null, BaseEntity::getCreateTime, entity.getCreateTimeStart())
                .ge(entity.getUpdateTimeStart() != null, BaseEntity::getUpdateTime, entity.getUpdateTimeStart())
                .lt(entity.getUpdateTimeStart() != null, BaseEntity::getUpdateTime, entity.getUpdateTimeStart());
    }

    @Override
    public void appendUpdateWrapper(MatNewsReqVO entity, UpdateWrapper<MatNewsEntity> wrapper) {

    }
}
