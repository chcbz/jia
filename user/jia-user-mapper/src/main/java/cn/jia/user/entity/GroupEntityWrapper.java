package cn.jia.user.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.jia.core.util.CollectionUtil;

public class GroupEntityWrapper implements BaseEntityWrapper<GroupVO, GroupEntity> {
    @Override
    public void appendQueryWrapper(GroupVO entity, QueryWrapper<GroupEntity> wrapper) {
        Wrappers.lambdaQuery(GroupEntity.class)
                .like(StringUtil.isNotBlank(entity.getNameLike()), GroupEntity::getName, entity.getNameLike())
                .like(StringUtil.isNotBlank(entity.getCodeLike()), GroupEntity::getCode, entity.getCodeLike())
                .like(StringUtil.isNotBlank(entity.getRemarkLike()), GroupEntity::getRemark, entity.getRemarkLike())
                .in(CollectionUtil.isNotNullOrEmpty(entity.getUserIds()), GroupEntity::getId, entity.getUserIds())
                .in(CollectionUtil.isNotNullOrEmpty(entity.getRoleIds()), GroupEntity::getId, entity.getRoleIds());
    }

    @Override
    public void appendUpdateWrapper(GroupVO entity, UpdateWrapper<GroupEntity> wrapper) {

    }
}
