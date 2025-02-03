package cn.jia.mat.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class MatMediaReqVOWrapper implements BaseEntityWrapper<MatMediaReqVO, MatMediaEntity> {
    @Override
    public void appendQueryWrapper(MatMediaReqVO entity, QueryWrapper<MatMediaEntity> wrapper) {
        wrapper.lambda()
                .like(StringUtil.isNotEmpty(entity.getTitleLike()), MatMediaEntity::getTitle, entity.getTitleLike())
                .ge(entity.getTimeStart() != null, BaseEntity::getUpdateTime, entity.getTimeStart())
                .lt(entity.getTimeEnd() != null, BaseEntity::getUpdateTime, entity.getTimeEnd());
    }

    @Override
    public void appendUpdateWrapper(MatMediaReqVO entity, UpdateWrapper<MatMediaEntity> wrapper) {

    }
}
