package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserGroupDao;
import cn.jia.user.entity.GroupEntity;
import cn.jia.user.mapper.GroupMapper;
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
public class UserGroupDaoImpl extends BaseDaoImpl<GroupMapper, GroupEntity> implements UserGroupDao {
    @Override
    public List<GroupEntity> selectByUserId(Long userId) {
        return baseMapper.selectByUserId(userId);
    }

    @Override
    public GroupEntity selectByCode(String code) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(GroupEntity.class).eq(GroupEntity::getCode, code));
    }
}
