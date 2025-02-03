package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserPermsRelDao;
import cn.jia.user.entity.PermsRelEntity;
import cn.jia.user.mapper.PermsRelMapper;
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
public class UserPermsRelDaoImpl extends BaseDaoImpl<PermsRelMapper, PermsRelEntity> implements UserPermsRelDao {

    @Override
    public List<PermsRelEntity> selectByRoleId(Long roleId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(PermsRelEntity.class).eq(PermsRelEntity::getRoleId, roleId));
    }

    @Override
    public List<PermsRelEntity> selectByUserId(Long userId) {
        return baseMapper.selectByUserId(userId);
    }
}
