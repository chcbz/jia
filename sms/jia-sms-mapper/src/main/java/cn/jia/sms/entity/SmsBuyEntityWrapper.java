package cn.jia.sms.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class SmsBuyEntityWrapper implements BaseEntityWrapper<SmsBuyVO, SmsBuyEntity> {
    @Override
    public void appendQueryWrapper(SmsBuyVO entity, QueryWrapper<SmsBuyEntity> wrapper) {
        wrapper.lambda().ge(entity.getTimeStart() != null, BaseEntity::getUpdateTime, entity.getTimeStart())
                .lt(entity.getTimeEnd() != null, BaseEntity::getUpdateTime, entity.getTimeEnd());
    }

    @Override
    public void appendUpdateWrapper(SmsBuyVO entity, UpdateWrapper<SmsBuyEntity> wrapper) {

    }
}
