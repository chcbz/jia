package cn.jia.sms.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class SmsTemplateEntityWrapper implements BaseEntityWrapper<SmsTemplateVO, SmsTemplateEntity> {
    @Override
    public void appendQueryWrapper(SmsTemplateVO entity, QueryWrapper<SmsTemplateEntity> wrapper) {
        wrapper.lambda().ge(entity.getUpdateTimeStart() != null, BaseEntity::getUpdateTime, entity.getUpdateTimeStart())
                .lt(entity.getUpdateTimeEnd() != null, BaseEntity::getUpdateTime, entity.getUpdateTimeEnd())
                .ge(entity.getCreateTimeStart() != null, BaseEntity::getUpdateTime, entity.getCreateTimeStart())
                .lt(entity.getCreateTimeEnd() != null, BaseEntity::getUpdateTime, entity.getCreateTimeEnd())
                .like(StringUtil.isNotEmpty(entity.getNameLike()), SmsTemplateEntity::getName, entity.getNameLike());
    }

    @Override
    public void appendUpdateWrapper(SmsTemplateVO entity, UpdateWrapper<SmsTemplateEntity> wrapper) {

    }
}
