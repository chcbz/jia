package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.AuthEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserAuthDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserAuthDaoImpl userAuthDao;

    @Test
    void selectByRoleId() {
        List<AuthEntity> authEntityList = userAuthDao.selectByRoleId(1L);
        assertNotNull(authEntityList);
    }

    @Test
    void selectByUserId() {
        List<AuthEntity> authEntityList = userAuthDao.selectByUserId(1L);
        assertNotNull(authEntityList);
    }
}