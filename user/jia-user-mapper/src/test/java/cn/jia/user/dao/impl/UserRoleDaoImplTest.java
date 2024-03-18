package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.RoleEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserRoleDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserRoleDaoImpl userRoleDao;

    @Test
    void selectByUserId() {
        List<RoleEntity> roleEntities = userRoleDao.selectByUserId(1L);
        assertNotNull(roleEntities);
        assertEquals(2, roleEntities.size());
    }

    @Test
    void selectByGroupId() {
        List<RoleEntity> roleEntities = userRoleDao.selectByGroupId(1L);
        assertNotNull(roleEntities);
        assertEquals(1, roleEntities.size());
    }

    @Test
    void selectByCode() {
        RoleEntity roleEntity = userRoleDao.selectByCode("AdministratorAccess");
        assertNotNull(roleEntity);
    }
}