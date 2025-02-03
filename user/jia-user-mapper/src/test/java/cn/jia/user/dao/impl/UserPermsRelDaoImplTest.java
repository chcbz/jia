package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.PermsRelEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserPermsRelDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserPermsRelDaoImpl userAuthDao;

    @Test
    void selectByRoleId() {
        List<PermsRelEntity> permsRelEntityList = userAuthDao.selectByRoleId(1L);
        assertNotNull(permsRelEntityList);
    }

    @Test
    void selectByUserId() {
        List<PermsRelEntity> permsRelEntityList = userAuthDao.selectByUserId(1L);
        assertNotNull(permsRelEntityList);
    }
}