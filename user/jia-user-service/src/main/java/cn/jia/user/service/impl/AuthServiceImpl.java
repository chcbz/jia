package cn.jia.user.service.impl;

import cn.jia.core.service.BaseServiceImpl;
import cn.jia.user.dao.UserAuthDao;
import cn.jia.user.entity.AuthEntity;
import cn.jia.user.service.AuthService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthServiceImpl extends BaseServiceImpl<UserAuthDao, AuthEntity> implements AuthService {
    @Override
    public List<AuthEntity> findByUserId(Long userId) {
        return baseDao.selectByUserId(userId);
    }

    @Override
    public List<AuthEntity> findByRoleId(Long roleId) {
        return baseDao.selectByRoleId(roleId);
    }
}
