package cn.jia.user.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.dao.UserRelationRow;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserVO;
import cn.jia.user.entity.UserVOWrapper;
import cn.jia.user.mapper.InfoMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

import java.util.Collection;
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

    @Override
    public List<UserEntity> selectForList(UserVO user) {
        UserVO query = user == null ? new UserVO() : user;
        String tenantId = query.getTenantId();
        String clientId = query.getClientId();
        query.setTenantId(null);
        query.setClientId(null);
        try {
            QueryWrapper<UserEntity> wrapper = new QueryWrapper<>(query);
            new UserVOWrapper().appendQueryWrapper(query, wrapper);
            appendExactScope(wrapper, "tenant_id", tenantId);
            appendExactScope(wrapper, "client_id", clientId);
            return baseMapper.selectList(wrapper);
        } finally {
            query.setTenantId(tenantId);
            query.setClientId(clientId);
        }
    }

    @Override
    public List<UserRelationRow> selectRelationsByUserIds(Collection<Long> userIds) {
        return baseMapper.selectRelationsByUserIds(userIds);
    }

    private void appendExactScope(QueryWrapper<UserEntity> wrapper, String column, String value) {
        if (value != null) {
            wrapper.apply("OCTET_LENGTH(" + column + ") = OCTET_LENGTH({0}) "
                    + "AND CAST(" + column + " AS BINARY(200)) = CAST({0} AS BINARY(200))", value);
        }
    }

    @Override
    public UserEntity selectSecurityById(long userId) {
        return baseMapper.selectSecurityById(userId);
    }

    @Override
    public List<UserEntity> selectSecurityByExactJiacn(String jiacn) {
        return baseMapper.selectSecurityByExactJiacn(jiacn);
    }

    @Override
    public int incrementAuthEpoch(long userId, long expectedEpoch) {
        return baseMapper.incrementAuthEpoch(userId, expectedEpoch);
    }

    @Override
    public int incrementPoint(String jiacn, int add, long updateTime) {
        return baseMapper.incrementPoint(jiacn, add, updateTime);
    }

    @Override
    public Integer selectPointByJiacn(String jiacn) {
        return baseMapper.selectPointByJiacn(jiacn);
    }
}
