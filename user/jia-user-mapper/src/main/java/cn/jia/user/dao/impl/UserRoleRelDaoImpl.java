package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserRoleRelDao;
import cn.jia.user.entity.RoleRelEntity;
import cn.jia.user.mapper.RoleRelMapper;
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
public class UserRoleRelDaoImpl extends BaseDaoImpl<RoleRelMapper, RoleRelEntity> implements UserRoleRelDao {

    @Override
    public List<RoleRelEntity> selectByGroupId(Long groupId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(RoleRelEntity.class).eq(RoleRelEntity::getGroupId, groupId));
    }

    @Override
    public List<RoleRelEntity> selectByUserId(Long userId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(RoleRelEntity.class).eq(RoleRelEntity::getUserId, userId));
    }

    @Override
    public List<RoleRelEntity> selectByRoleId(Long roleId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(RoleRelEntity.class).eq(RoleRelEntity::getRoleId, roleId));
    }
}
