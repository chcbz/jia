package cn.jia.sms.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class SmsPackageEntityWrapper implements BaseEntityWrapper<SmsPackageEntity, SmsPackageEntity> {
    @Override
    public void appendQueryWrapper(SmsPackageEntity entity, QueryWrapper<SmsPackageEntity> wrapper) {
        wrapper.lambda().orderByAsc(SmsPackageEntity::getOrder);
    }

    @Override
    public void appendUpdateWrapper(SmsPackageEntity entity, UpdateWrapper<SmsPackageEntity> wrapper) {

    }
}
