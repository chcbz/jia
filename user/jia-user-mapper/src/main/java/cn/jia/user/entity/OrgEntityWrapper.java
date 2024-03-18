package cn.jia.user.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.jia.core.util.CollectionUtil;

public class OrgEntityWrapper implements BaseEntityWrapper<OrgVO, OrgEntity> {
    @Override
    public void appendQueryWrapper(OrgVO entity, QueryWrapper<OrgEntity> wrapper) {
        Wrappers.lambdaQuery(OrgEntity.class)
                .like(StringUtils.isNotBlank(entity.getNameLike()), OrgEntity::getName, entity.getNameLike())
                .like(StringUtils.isNotBlank(entity.getCodeLike()), OrgEntity::getCode, entity.getCodeLike())
                .like(StringUtils.isNotBlank(entity.getRemarkLike()), OrgEntity::getRemark, entity.getRemarkLike())
                .in(CollectionUtil.isNotNullOrEmpty(entity.getUserIds()), OrgEntity::getId, entity.getUserIds())
                .in(CollectionUtil.isNotNullOrEmpty(entity.getOrgIds()), OrgEntity::getId, entity.getOrgIds());
    }

    @Override
    public void appendUpdateWrapper(OrgVO entity, UpdateWrapper<OrgEntity> wrapper) {

    }
}
