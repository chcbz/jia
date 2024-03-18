package cn.jia.user.dao.impl;

import cn.jia.test.BaseDbUnitTest;
import cn.jia.user.entity.OrgRelEntity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserOrgRelDaoImplTest extends BaseDbUnitTest {
    @Inject
    UserOrgRelDaoImpl userOrgRelDao;

    @Test
    void selectByUserId() {
        List<OrgRelEntity> orgRelEntityList = userOrgRelDao.selectByUserId(1L);
        assertNotNull(orgRelEntityList);
    }

    @Test
    void selectByOrgId() {
        List<OrgRelEntity> orgRelEntityList = userOrgRelDao.selectByOrgId(1L);
        assertNotNull(orgRelEntityList);
        assertEquals(1, orgRelEntityList.size());
    }
}