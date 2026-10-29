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
    public List<UserEntity> selectOwnIdentityByExactJiacn(String jiacn) {
        requireExactIdentity(jiacn);
        return baseMapper.selectOwnIdentityByExactJiacn(jiacn);
    }

    private void requireExactIdentity(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 50
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)
                || hasUnpairedSurrogate(value)) {
            throw new IllegalArgumentException("jiacn is invalid");
        }
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
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
        return searchByExample(user, null);
    }

    @Override
    public List<UserEntity> searchByExample(UserEntity user, String pointTenantId) {
        requirePointScope(user, pointTenantId);
        return baseMapper.searchByExample(user, pointTenantId);
    }

    @Override
    public List<UserEntity> selectForList(UserVO user) {
        return selectForList(user, null);
    }

    private void requirePointScope(UserEntity user, String pointTenantId) {
        if (user != null && user.getPoint() != null && (pointTenantId == null || pointTenantId.isBlank())) {
            throw new IllegalArgumentException("Point filtering requires a fixed business tenant");
        }
    }

    @Override
    public List<UserEntity> selectForList(UserVO user, String pointTenantId) {
        requirePointScope(user, pointTenantId);
        UserVO query = user == null ? new UserVO() : user;
        String tenantId = query.getTenantId();
        String clientId = query.getClientId();
        query.setTenantId(null);
        query.setClientId(null);
        try {
            QueryWrapper<UserEntity> wrapper = new QueryWrapper<>(query);
            new UserVOWrapper().appendQueryWrapper(query, wrapper);
            if (query.getPoint() != null) {
                wrapper.apply("COALESCE((SELECT pa.balance FROM point_account pa "
                        + "WHERE pa.tenant_id={0} AND OCTET_LENGTH(pa.tenant_id)=OCTET_LENGTH({0}) "
                        + "AND pa.jiacn=user_info.jiacn "
                        + "AND CAST(pa.jiacn AS BINARY)=CAST(user_info.jiacn AS BINARY)),0)={1}",
                        pointTenantId, query.getPoint());
            }
            appendMembershipFilter(wrapper, tenantId, clientId);
            if (pointTenantId != null) {
                // Keep BaseMapper's entity predicates and UserVOWrapper's table-qualified
                // membership predicates; customSqlSegment alone omits entity conditions.
                wrapper.select(UserEntity.class, field -> true);
                String identityColumns = wrapper.getSqlSelect();
                wrapper.getParamNameValuePairs().put("pointBusinessTenant", pointTenantId);
                String tenant = "#{ew.paramNameValuePairs.pointBusinessTenant}";
                wrapper.select(identityColumns, "COALESCE((SELECT pa.balance FROM point_account pa "
                        + "WHERE pa.tenant_id=" + tenant
                        + " AND OCTET_LENGTH(pa.tenant_id)=OCTET_LENGTH(" + tenant + ")"
                        + " AND pa.jiacn=user_info.jiacn"
                        + " AND CAST(pa.jiacn AS BINARY)=CAST(user_info.jiacn AS BINARY)),0) AS point");
            }
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

    private void appendMembershipFilter(QueryWrapper<UserEntity> wrapper, String tenantId, String clientId) {
        if (tenantId == null && clientId == null) return;
        String sql = "EXISTS (SELECT 1 FROM user_business_membership bm WHERE bm.user_id=user_info.id";
        java.util.List<Object> values = new java.util.ArrayList<>();
        if (tenantId != null) {
            sql += " AND bm.tenant_id={0} AND OCTET_LENGTH(bm.tenant_id)=OCTET_LENGTH({0})";
            values.add(tenantId);
        }
        if (clientId != null) {
            // Empty stored client represents old NULL, not an empty application ID.
            String index = "{" + values.size() + "}";
            sql += " AND bm.client_id<>'' AND bm.client_id=" + index
                    + " AND OCTET_LENGTH(bm.client_id)=OCTET_LENGTH(" + index + ")";
            values.add(clientId);
        }
        wrapper.apply(sql + ")", values.toArray());
    }

    /** Generic filtered identity lists must not reintroduce BaseDao's tenant-zero union. */
    @Override
    public List<UserEntity> selectByEntity(UserEntity query) {
        requirePointScope(query, null);
        QueryWrapper<UserEntity> wrapper = new QueryWrapper<>(query);
        appendQueryWrapper(query, wrapper);
        appendMembershipFilter(wrapper, query.getTenantId(), query.getClientId());
        return baseMapper.selectList(wrapper);
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


}
