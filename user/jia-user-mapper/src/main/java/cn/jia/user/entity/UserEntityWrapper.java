package cn.jia.user.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.jia.core.util.CollectionUtil;

public class UserEntityWrapper implements BaseEntityWrapper<UserVO, UserEntity> {
    @Override
    public void appendQueryWrapper(UserVO entity, QueryWrapper<UserEntity> wrapper) {
        Wrappers.lambdaQuery(UserEntity.class)
                .like(StringUtils.isNotBlank(entity.getUsernameLike()), UserEntity::getUsername,
                        entity.getUsernameLike())
                .like(StringUtils.isNotBlank(entity.getOpenidLike()), UserEntity::getOpenid, entity.getOpenidLike())
                .like(StringUtils.isNotBlank(entity.getJiacnLike()), UserEntity::getJiacn, entity.getJiacnLike())
                .like(StringUtils.isNotBlank(entity.getPhoneLike()), UserEntity::getPhone, entity.getPhoneLike())
                .like(StringUtils.isNotBlank(entity.getEmailLike()), UserEntity::getEmail, entity.getEmailLike())
                .like(StringUtils.isNotBlank(entity.getNicknameLike()), UserEntity::getNickname, entity.getNicknameLike())
                .like(StringUtils.isNotBlank(entity.getCityLike()), UserEntity::getCity, entity.getCityLike())
                .like(StringUtils.isNotBlank(entity.getProvinceLike()), UserEntity::getProvince, entity.getProvinceLike())
                .like(StringUtils.isNotBlank(entity.getCountryLike()), UserEntity::getCountry, entity.getCountryLike())
                .like(StringUtils.isNotBlank(entity.getReferrerLike()), UserEntity::getReferrer,
                        entity.getReferrerLike())
                .ge(entity.getCreateTimeStart() != null, UserEntity::getCreateTime, entity.getCreateTimeStart())
                .le(entity.getCreateTimeEnd() != null, UserEntity::getCreateTime, entity.getCreateTimeEnd())
                .ge(entity.getUpdateTimeStart() != null, UserEntity::getUpdateTime, entity.getUpdateTimeStart())
                .le(entity.getUpdateTimeEnd() != null, UserEntity::getUpdateTime, entity.getUpdateTimeEnd())
                .in(CollectionUtil.isNotNullOrEmpty(entity.getJiacnList()), UserEntity::getJiacn, entity.getJiacnList())
                .exists(entity.getOrgId() != null,
                        "select 1 from user_org_rel where user_org_rel.user_id = user_info.id and user_org_rel.org_id" +
                                " = %s", entity.getOrgId())
                .exists(entity.getGroupId() != null,
                        "select 1 from user_group_rel where user_group_rel.user_id = user_info.id and user_group_rel" +
                                ".group_id = %s", entity.getGroupId())
                .exists(entity.getRoleId() != null,
                        "select 1 from user_role_rel where user_role_rel.user_id = user_info.id and user_role_rel" +
                                ".role_id = %s", entity.getRoleId());
    }

    @Override
    public void appendUpdateWrapper(UserVO entity, UpdateWrapper<UserEntity> wrapper) {

    }
}
