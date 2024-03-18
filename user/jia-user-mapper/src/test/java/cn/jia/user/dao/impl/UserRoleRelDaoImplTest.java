package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.RoleRelEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserRoleRelDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserRoleRelDaoImpl userRoleRelDao;

    @Test
    void selectByGroupId() {
        List<RoleRelEntity> roleRelEntities = userRoleRelDao.selectByGroupId(1L);
        assertNotNull(roleRelEntities);
        assertEquals(1, roleRelEntities.size());
    }

    @Test
    void selectByUserId() {
        List<RoleRelEntity> roleRelEntities = userRoleRelDao.selectByUserId(1L);
        assertNotNull(roleRelEntities);
        assertEquals(1, roleRelEntities.size());
    }

    @Test
    void selectByRoleId() {
        List<RoleRelEntity> roleRelEntities = userRoleRelDao.selectByRoleId(1L);
        assertNotNull(roleRelEntities);
        assertEquals(2, roleRelEntities.size());
    }
}