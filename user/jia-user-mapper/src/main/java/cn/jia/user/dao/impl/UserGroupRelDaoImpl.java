package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserGroupRelDao;
import cn.jia.user.entity.GroupRelEntity;
import cn.jia.user.mapper.GroupRelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
public class UserGroupRelDaoImpl extends BaseDaoImpl<GroupRelMapper, GroupRelEntity> implements UserGroupRelDao {

    @Override
    public List<GroupRelEntity> selectByGroupId(Long groupId) {
        LambdaQueryWrapper<GroupRelEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(GroupRelEntity::getGroupId, groupId);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public List<GroupRelEntity> selectByUserId(Long userId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(GroupRelEntity.class).eq(GroupRelEntity::getUserId, userId));
    }
}
