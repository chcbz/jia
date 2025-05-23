package cn.jia.user.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import cn.jia.core.util.CollectionUtil;

public class RoleVOWrapper implements BaseEntityWrapper<RoleVO, RoleEntity> {
    @Override
    public void appendQueryWrapper(RoleVO entity, QueryWrapper<RoleEntity> wrapper) {
        wrapper.lambda()
                .like(StringUtil.isNotBlank(entity.getNameLike()), RoleEntity::getName, entity.getNameLike())
                .like(StringUtil.isNotBlank(entity.getCodeLike()), RoleEntity::getCode, entity.getCodeLike())
                .like(StringUtil.isNotBlank(entity.getRemarkLike()), RoleEntity::getRemark, entity.getRemarkLike())
                .in(CollectionUtil.isNotNullOrEmpty(entity.getUserIds()), RoleEntity::getId, entity.getUserIds());
    }

    @Override
    public void appendUpdateWrapper(RoleVO entity, UpdateWrapper<RoleEntity> wrapper) {

    }
}
