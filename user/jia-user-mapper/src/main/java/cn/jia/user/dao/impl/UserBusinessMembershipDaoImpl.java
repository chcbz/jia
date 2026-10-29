package cn.jia.user.dao.impl;

import cn.jia.user.dao.UserBusinessMembershipDao;
import cn.jia.user.mapper.UserBusinessMembershipMapper;
import jakarta.inject.Named;

@Named
public class UserBusinessMembershipDaoImpl implements UserBusinessMembershipDao {
    private final UserBusinessMembershipMapper mapper;
    public UserBusinessMembershipDaoImpl(UserBusinessMembershipMapper mapper) { this.mapper = mapper; }
    @Override public void deleteByUserId(java.io.Serializable userId) { mapper.deleteByUserId(userId); }
    @Override public void ensure(String tenantId, String clientId, long userId, long now) {
        mapper.ensure(tenantId, clientId, userId, now);
    }
}
