package cn.jia.user.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.jia.core.util.CollectionUtil;

public class RoleEntityWrapper implements BaseEntityWrapper<RoleVO, RoleEntity> {
    @Override
    public void appendQueryWrapper(RoleVO entity, QueryWrapper<RoleEntity> wrapper) {
        Wrappers.lambdaQuery(RoleEntity.class)
                .like(StringUtils.isNotBlank(entity.getNameLike()), RoleEntity::getName, entity.getNameLike())
                .like(StringUtils.isNotBlank(entity.getCodeLike()), RoleEntity::getCode, entity.getCodeLike())
                .like(StringUtils.isNotBlank(entity.getRemarkLike()), RoleEntity::getRemark, entity.getRemarkLike())
                .in(CollectionUtil.isNotNullOrEmpty(entity.getUserIds()), RoleEntity::getId, entity.getUserIds());
    }

    @Override
    public void appendUpdateWrapper(RoleVO entity, UpdateWrapper<RoleEntity> wrapper) {

    }
}
