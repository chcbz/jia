package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserAuthDao;
import cn.jia.user.entity.AuthEntity;
import cn.jia.user.mapper.AuthMapper;
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
public class UserAuthDaoImpl extends BaseDaoImpl<AuthMapper, AuthEntity> implements UserAuthDao {

    @Override
    public List<AuthEntity> selectByRoleId(Long roleId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(AuthEntity.class).eq(AuthEntity::getRoleId, roleId));
    }

    @Override
    public List<AuthEntity> selectByUserId(Long userId) {
        return baseMapper.selectByUserId(userId);
    }
}
