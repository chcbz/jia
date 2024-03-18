package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserVO;
import cn.jia.user.mapper.InfoMapper;
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
public class UserInfoDaoImpl extends BaseDaoImpl<InfoMapper, UserEntity> implements UserInfoDao {

    @Override
    public UserEntity selectByJiacn(String jiacn) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class).eq(UserEntity::getJiacn, jiacn));
    }

    @Override
    public UserEntity selectByUsername(String username) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class).eq(UserEntity::getUsername, username));
    }

    @Override
    public UserEntity selectByOpenid(String openid) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class).eq(UserEntity::getOpenid, openid));
    }

    @Override
    public UserEntity selectByPhone(String phone) {
        return baseMapper.selectOne(Wrappers.lambdaQuery(UserEntity.class).eq(UserEntity::getPhone, phone));
    }

    @Override
    public List<UserEntity> selectByRole(Long roleId) {
        return baseMapper.selectByRole(roleId);
    }

    @Override
    public List<UserEntity> selectByGroup(Long groupId) {
        return baseMapper.selectByGroup(groupId);
    }

    @Override
    public List<UserEntity> selectByOrg(Long orgId) {
        return baseMapper.selectByOrg(orgId);
    }

    @Override
    public List<UserEntity> searchByExample(UserEntity user) {
        return baseMapper.searchByExample(user);
    }
}
