package cn.jia.user.entity;

import cn.jia.common.entity.BaseEntityWrapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import cn.jia.core.util.CollectionUtil;

public class UserEntityWrapper implements BaseEntityWrapper<UserVO, UserEntity> {
    @Override
    public void appendQueryWrapper(UserVO entity, QueryWrapper<UserEntity> wrapper) {
        Wrappers.lambdaQuery(UserEntity.class)
                .like(StringUtil.isNotBlank(entity.getUsernameLike()), UserEntity::getUsername,
                        entity.getUsernameLike())
                .like(StringUtil.isNotBlank(entity.getOpenidLike()), UserEntity::getOpenid, entity.getOpenidLike())
                .like(StringUtil.isNotBlank(entity.getJiacnLike()), UserEntity::getJiacn, entity.getJiacnLike())
                .like(StringUtil.isNotBlank(entity.getPhoneLike()), UserEntity::getPhone, entity.getPhoneLike())
                .like(StringUtil.isNotBlank(entity.getEmailLike()), UserEntity::getEmail, entity.getEmailLike())
                .like(StringUtil.isNotBlank(entity.getNicknameLike()), UserEntity::getNickname, entity.getNicknameLike())
                .like(StringUtil.isNotBlank(entity.getCityLike()), UserEntity::getCity, entity.getCityLike())
                .like(StringUtil.isNotBlank(entity.getProvinceLike()), UserEntity::getProvince, entity.getProvinceLike())
                .like(StringUtil.isNotBlank(entity.getCountryLike()), UserEntity::getCountry, entity.getCountryLike())
                .like(StringUtil.isNotBlank(entity.getReferrerLike()), UserEntity::getReferrer,
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
