package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserRoleDao;
import cn.jia.user.entity.RoleEntity;
import cn.jia.user.mapper.RoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-11-20
 */
@Named
public class UserRoleDaoImpl extends BaseDaoImpl<RoleMapper, RoleEntity> implements UserRoleDao {
    @Override
    public List<RoleEntity> selectByUserId(Long userId) {
        return baseMapper.selectByUserId(userId);
    }

    @Override
    public List<RoleEntity> selectByGroupId(Long groupId) {
        return baseMapper.selectByGroupId(groupId);
    }

    @Override
    public RoleEntity selectByCode(String code) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(RoleEntity.class).eq(RoleEntity::getCode, code));
    }
}
