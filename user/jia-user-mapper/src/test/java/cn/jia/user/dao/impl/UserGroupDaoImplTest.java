package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.GroupEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserGroupDaoImplTest extends BaseDbUnitTest {

    @Inject
    UserGroupDaoImpl userGroupDao;

    @Test
    void selectByUserId() {
        List<GroupEntity> groupEntityList = userGroupDao.selectByUserId(1L);
        assertNotNull(groupEntityList);
    }

    @Test
    void selectByCode() {
        GroupEntity groupEntity = userGroupDao.selectByCode("Finance");
        assertNotNull(groupEntity);
    }
}