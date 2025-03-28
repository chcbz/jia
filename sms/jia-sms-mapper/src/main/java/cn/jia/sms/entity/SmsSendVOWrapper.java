package cn.jia.sms.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class SmsSendVOWrapper implements BaseEntityWrapper<SmsSendVO, SmsSendEntity> {
    @Override
    public void appendQueryWrapper(SmsSendVO entity, QueryWrapper<SmsSendEntity> wrapper) {
        wrapper.lambda().ge(entity.getTimeStart() != null, BaseEntity::getUpdateTime, entity.getTimeStart())
                .lt(entity.getTimeEnd() != null, BaseEntity::getUpdateTime, entity.getTimeEnd());
    }

    @Override
    public void appendUpdateWrapper(SmsSendVO entity, UpdateWrapper<SmsSendEntity> wrapper) {

    }
}
