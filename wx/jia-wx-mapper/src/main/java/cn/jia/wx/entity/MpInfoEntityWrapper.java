package cn.jia.wx.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

public class MpInfoEntityWrapper implements BaseEntityWrapper<MpInfoVO, MpInfoEntity> {
    @Override
    public void appendQueryWrapper(MpInfoVO entity, QueryWrapper<MpInfoEntity> wrapper) {
        wrapper.lambda().in(CollectionUtil.isNotNullOrEmpty(
                entity.getClientIdList()), MpInfoEntity::getClientId, entity.getClientIdList());
    }

    @Override
    public void appendUpdateWrapper(MpInfoVO entity, UpdateWrapper<MpInfoEntity> wrapper) {

    }
}
