package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.GroupRelEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserGroupRelDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserGroupRelDaoImpl userGroupRelDao;

    @Test
    void selectByGroupId() {
        List<GroupRelEntity> groupRelEntities = userGroupRelDao.selectByGroupId(1L);
        assertNotNull(groupRelEntities);
        assertEquals(1, groupRelEntities.size());
    }

    @Test
    void selectByUserId() {
        List<GroupRelEntity> groupRelEntities = userGroupRelDao.selectByUserId(1L);
        assertNotNull(groupRelEntities);
        assertEquals(1, groupRelEntities.size());
    }
}